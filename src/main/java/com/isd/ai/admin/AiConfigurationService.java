package com.isd.ai.admin;

import com.isd.ai.admin.AiAdminDtos.*;
import com.isd.auth.security.CurrentUserProvider;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class AiConfigurationService {
    private final AiConfigurationMapper mapper;
    private final AiCredentialCipher cipher;
    private final AiEndpointPolicy endpoints;
    private final CurrentUserProvider users;
    private final ObjectProvider<AiConnectionTester> tester;
    private final TransactionTemplate transactions;
    private final String environmentKey;
    private final String environmentBase;
    private final String environmentModel;

    public AiConfigurationService(AiConfigurationMapper mapper,AiCredentialCipher cipher,
            AiEndpointPolicy endpoints,CurrentUserProvider users,ObjectProvider<AiConnectionTester> tester,
            PlatformTransactionManager transactionManager,
            @Value("${spring.ai.openai.api-key:local-placeholder}") String key,
            @Value("${spring.ai.openai.base-url:https://api.deepseek.com}") String base,
            @Value("${spring.ai.openai.chat.options.model:deepseek-chat}") String model) {
        this.mapper=mapper;this.cipher=cipher;this.endpoints=endpoints;this.users=users;this.tester=tester;
        this.transactions=new TransactionTemplate(transactionManager);
        this.environmentKey=key;this.environmentBase=base;this.environmentModel=model;
    }

    public List<ConfigResponse> list() {
        AiRuntimeRow state=mapper.runtime();
        return mapper.list().stream().map(row->response(row,state)).toList();
    }
    public List<ConfigResponse> versions(long id) {
        required(mapper.latest(id));
        AiRuntimeRow state=mapper.runtime();
        return mapper.versions(id).stream().map(row->response(row,state)).toList();
    }

    @Transactional
    public ConfigResponse create(SaveRequest input) {
        validate(input);
        String key=key(input.apiKey());
        AiConfigurationRow row=populate(input);
        row.createdBy=users.requireId();row.version=1L;
        // Ensure a missing deployment key fails before a configuration row is allocated.
        if(!cipher.ready()) cipher.encrypt(key,"readiness");
        mapper.insertConfiguration(row);
        row.keyCiphertext=cipher.encrypt(key,identity(row.id,row.version));
        row.keySuffix=suffix(key);
        mapper.insertVersion(row);
        mapper.audit(row.createdBy,"CREATE",row.id,row.version);
        return response(mapper.version(row.id,row.version),mapper.runtime());
    }

    @Transactional
    public ConfigResponse update(long id,SaveRequest input) {
        validate(input);
        AiConfigurationRow locked=required(mapper.lockConfiguration(id));
        usable(locked);
        if(!Objects.equals(locked.latestVersion,input.version())) throw conflict("配置已被修改，请刷新后重试");
        AiConfigurationRow previous=required(mapper.version(id,locked.latestVersion));
        String key=input.apiKey()==null || input.apiKey().isBlank()
                ? cipher.decrypt(previous.keyCiphertext,identity(id,previous.version)):key(input.apiKey());
        AiConfigurationRow row=populate(input);
        row.id=id;row.version=locked.latestVersion+1;row.createdBy=users.requireId();
        row.keyCiphertext=cipher.encrypt(key,identity(id,row.version));row.keySuffix=suffix(key);
        mapper.insertVersion(row);mapper.advance(id,row.version);
        mapper.audit(row.createdBy,"EDIT",id,row.version);
        return response(mapper.version(id,row.version),mapper.runtime());
    }

    public ConfigResponse test(long id,long version) {
        AiConfigurationRow row=required(mapper.version(id,version));usable(row);
        if(!Objects.equals(row.latestVersion,version)) throw conflict("只能测试最新保存的配置版本");
        String status="PASSED",message="认证、模型和工具调用测试通过";
        AiRuntimeSnapshot snapshot=snapshot(row);
        try { tester.getObject().test(snapshot); }
        catch(Exception ex) { status="FAILED";message="连接测试失败，请检查 Key、模型、接口地址或供应商状态"; }
        final String finalStatus=status, finalMessage=message;
        return transactions.execute(transaction->{
            if(mapper.tested(id,version,finalStatus,finalMessage)!=1) throw conflict("测试期间配置已被修改，请重新测试最新版本");
            mapper.audit(users.requireId(),"TEST_"+finalStatus,id,version);
            return response(mapper.version(id,version),mapper.runtime());
        });
    }

    @Transactional
    public RuntimeResponse setRuntime(RuntimeRequest input) {
        AiRuntimeRow state=mapper.lockRuntime();
        if(!Objects.equals(input.version(),state.version)) throw conflict("当前服务已被其他管理员修改，请刷新后重试");
        Long id=null,version=null;
        if("MANAGED".equals(input.mode())) {
            if(input.configurationId()==null || input.configurationVersion()==null) throw invalid("请选择已测试的配置版本");
            AiConfigurationRow locked=required(mapper.lockConfiguration(input.configurationId()));usable(locked);
            AiConfigurationRow row=required(mapper.version(input.configurationId(),input.configurationVersion()));
            if(!"PASSED".equals(row.testStatus)) throw conflict("请先测试该配置版本，测试通过后再设为当前服务");
            snapshot(row); // Validate that this deployment can decrypt the chosen key.
            id=row.id;version=row.version;
        }
        mapper.runtimeUpdate(input.mode(),id,version,users.requireId());
        mapper.audit(users.requireId(),"MANAGED".equals(input.mode())?"ACTIVATE":"DISABLE",id,version);
        return runtime();
    }

    @Transactional
    public void archive(long id,long version) {
        AiRuntimeRow state=mapper.lockRuntime();
        AiConfigurationRow row=required(mapper.lockConfiguration(id));
        if(!Objects.equals(row.latestVersion,version)) throw conflict("配置已被修改，请刷新后重试");
        if("MANAGED".equals(state.mode) && Objects.equals(state.configurationId,id)) {
            throw conflict("请先切换到其他服务或停用 AI，再归档当前配置");
        }
        if(Boolean.TRUE.equals(row.archived)) return;
        mapper.archive(id);mapper.eraseArchivedSecrets(id);
        mapper.audit(users.requireId(),"ARCHIVE",id,version);
    }

    /** A database read per invocation makes a switch visible across all application instances. */
    public Optional<AiRuntimeSnapshot> resolve() {
        AiRuntimeRow state=mapper.runtime();
        if("ENV".equals(state.mode)) return environmentConfigured()?Optional.of(environmentSnapshot()):Optional.empty();
        if(!"MANAGED".equals(state.mode)) return Optional.empty();
        try {
            AiConfigurationRow row=required(mapper.version(state.configurationId,state.configurationVersion));
            usable(row);
            return Optional.of(snapshot(row));
        } catch(BusinessException ex) { return Optional.empty(); }
    }

    public RuntimeResponse runtime() {
        AiRuntimeRow state=mapper.runtime();
        boolean ready=cipher.ready();
        if("ENV".equals(state.mode)) {
            String message=environmentConfigured()?"当前使用部署环境中的模型配置":"尚未配置模型 Key，当前使用知识库降级";
            if(!ready) message+="；后台 Key 管理需要配置部署主密钥";
            return new RuntimeResponse(state.mode,state.version,null,null,environmentProvider(),environmentModel,
                    environmentConfigured(),ready,message);
        }
        if("DISABLED".equals(state.mode)) return new RuntimeResponse(state.mode,state.version,null,null,null,null,false,ready,
                "AI 已由管理员停用，现有功能使用知识库降级");
        AiConfigurationRow row=mapper.version(state.configurationId,state.configurationVersion);
        String message="当前服务已启用，后续调用使用此版本";
        boolean configured=false;
        if(row!=null && !Boolean.TRUE.equals(row.archived)) {
            try { snapshot(row);configured=true; }
            catch(BusinessException ex) { message=ex.getMessage();ready=false; }
        } else { message="当前模型配置不可用，请重新选择服务"; }
        return new RuntimeResponse(state.mode,state.version,state.configurationId,state.configurationVersion,
                row==null?null:row.provider,row==null?null:row.model,configured,ready,message);
    }

    AiRuntimeSnapshot snapshot(AiConfigurationRow row) {
        return new AiRuntimeSnapshot(row.id,row.version,row.provider,row.name,
                endpoints.validate(row.provider,row.baseUrl),row.model,
                cipher.decrypt(row.keyCiphertext,identity(row.id,row.version)),row.keySuffix,
                row.currency,row.inputPrice,row.cachedInputPrice,row.outputPrice);
    }
    public AiRuntimeSnapshot configurationSnapshot(long id,long version) {
        AiConfigurationRow row=required(mapper.version(id,version));usable(row);return snapshot(row);
    }
    private AiRuntimeSnapshot environmentSnapshot() {
        return new AiRuntimeSnapshot(null,0L,environmentProvider(),"部署环境配置",environmentBase,environmentModel,
                environmentKey,suffix(environmentKey),null,null,null,null);
    }
    private boolean environmentConfigured() { return environmentKey!=null && !environmentKey.isBlank() && !"local-placeholder".equals(environmentKey); }
    private String environmentProvider() {
        try {
            String host=java.net.URI.create(environmentBase).getHost();
            if("api.deepseek.com".equalsIgnoreCase(host)) return "DEEPSEEK";
            if("api.openai.com".equalsIgnoreCase(host)) return "OPENAI";
            if(host!=null && (host.contains("dashscope") || host.endsWith(".maas.aliyuncs.com"))) return "QWEN";
        } catch(IllegalArgumentException ex) { /* Existing deployment values remain operator-managed. */ }
        return "CUSTOM";
    }
    private AiConfigurationRow populate(SaveRequest input) {
        AiConfigurationRow row=new AiConfigurationRow();
        row.name=input.name().trim();row.provider=input.provider();
        row.baseUrl=endpoints.validate(input.provider(),input.baseUrl());row.model=input.model().trim();
        row.currency=input.currency();row.inputPrice=input.inputPrice();row.cachedInputPrice=input.cachedInputPrice();row.outputPrice=input.outputPrice();
        return row;
    }
    private void validate(SaveRequest input) {
        if(input.name()==null || input.name().isBlank() || input.model()==null || input.model().isBlank()) throw invalid("请输入配置名称和模型名称");
        if((input.inputPrice()!=null || input.cachedInputPrice()!=null || input.outputPrice()!=null)
                && (input.currency()==null || input.currency().isBlank())) throw invalid("填写估算单价时必须选择计费币种");
        if(input.model().chars().anyMatch(Character::isISOControl)) throw invalid("模型名称不能包含控制字符");
    }
    private String key(String value) {
        if(value==null || value.isBlank() || value.length()<8 || value.length()>4096
                || value.chars().anyMatch(Character::isWhitespace) || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("请输入有效 API Key，长度至少为 8，且不能包含空白或控制字符");
        }
        return value;
    }
    private String suffix(String value) { return value.substring(Math.max(0,value.length()-4)); }
    private String identity(Long id,Long version) { return "isd-ai:"+id+":"+version; }
    private AiConfigurationRow required(AiConfigurationRow row) { if(row==null) throw new BusinessException(ErrorCode.NOT_FOUND,"模型配置不存在");return row; }
    private void usable(AiConfigurationRow row) { if(Boolean.TRUE.equals(row.archived)) throw conflict("配置已归档，请新建配置"); }
    private ConfigResponse response(AiConfigurationRow row,AiRuntimeRow state) {
        return new ConfigResponse(row.id,row.version,row.name,row.provider,row.baseUrl,row.model,row.keySuffix,
                row.currency,row.inputPrice,row.cachedInputPrice,row.outputPrice,row.testStatus,row.testMessage,
                row.testedAt,Boolean.TRUE.equals(row.archived),"MANAGED".equals(state.mode)
                && Objects.equals(state.configurationId,row.id) && Objects.equals(state.configurationVersion,row.version),row.createdAt);
    }
    private BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT,message); }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_PARAM,message); }
}
