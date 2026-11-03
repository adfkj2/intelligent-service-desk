package com.isd.ai.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.ai.admin.AiAdminDtos.*;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import com.isd.common.time.BusinessTime;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class AiBalanceService {
    private final AiConfigurationMapper mapper;
    private final AiConfigurationService configurations;
    private final ObjectMapper json;
    private final RestClient client;

    @Autowired
    public AiBalanceService(AiConfigurationMapper mapper,AiConfigurationService configurations,ObjectMapper json) {
        this.mapper=mapper;this.configurations=configurations;this.json=json;
        HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requests=new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofSeconds(10));
        this.client=RestClient.builder().requestFactory(requests).build();
    }

    AiBalanceService(AiConfigurationMapper mapper,AiConfigurationService configurations,ObjectMapper json,RestClient client) {
        this.mapper=mapper;this.configurations=configurations;this.json=json;this.client=client;
    }

    public BalanceResponse balance(long id) {
        AiConfigurationRow config=required(id);
        return response(config,mapper.balance(id,config.version));
    }

    public BalanceResponse refresh(long id) {
        AiConfigurationRow config=required(id);
        if(!"DEEPSEEK".equals(config.provider)) return response(config,null);
        if(Boolean.TRUE.equals(config.archived)) throw new BusinessException(ErrorCode.CONFLICT,"配置已归档，不能刷新余额");
        AiBalanceRow cached=mapper.balance(id,config.version);
        if(cached!=null && cached.attemptedAt!=null && cached.attemptedAt.isAfter(BusinessTime.now().minusSeconds(10))) {
            return response(config,cached);
        }
        try {
            AiRuntimeSnapshot snapshot=configurations.configurationSnapshot(id,config.version);
            // Fixed official URL: no custom balance endpoint can receive this credential.
            JsonNode body=client.get().uri("https://api.deepseek.com/user/balance")
                    .headers(headers->headers.setBearerAuth(snapshot.apiKey())).retrieve().body(JsonNode.class);
            if(body==null || !body.path("is_available").isBoolean() || !body.path("balance_infos").isArray()) throw new IllegalArgumentException();
            List<BalanceAmount> amounts=new ArrayList<>();
            for(JsonNode value:body.path("balance_infos")) {
                String currency=value.path("currency").asText();
                if(!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException();
                amounts.add(new BalanceAmount(currency,amount(value,"total_balance"),amount(value,"granted_balance"),amount(value,"topped_up_balance")));
            }
            if(amounts.isEmpty()) throw new IllegalArgumentException();
            mapper.balanceSuccess(id,config.version,json.writeValueAsString(amounts),body.path("is_available").asBoolean());
        } catch(Exception ex) {
            // Provider errors can contain authentication headers or echoed inputs. Persist only our fixed message.
            mapper.balanceFailure(id,config.version,"余额查询失败，请检查 Key、网络或供应商状态；已保留上次查询结果");
        }
        return response(config,mapper.balance(id,config.version));
    }

    private BalanceResponse response(AiConfigurationRow config,AiBalanceRow row) {
        String console=switch(config.provider) {
            case "DEEPSEEK"->"https://platform.deepseek.com/usage";
            case "OPENAI"->"https://platform.openai.com/usage";
            case "QWEN"->"https://bailian.console.aliyun.com/";
            default->null;
        };
        if(!"DEEPSEEK".equals(config.provider)) return new BalanceResponse(false,null,false,null,null,
                "此供应商暂不支持使用模型 Key 自动查询余额，请前往官方账务控制台",console,List.of());
        List<BalanceAmount> amounts=List.of();
        boolean invalidCache=false;
        if(row!=null && row.balanceJson!=null) {
            try { amounts=json.readValue(row.balanceJson,new TypeReference<List<BalanceAmount>>(){}); }
            catch(Exception ex) { invalidCache=true; }
        }
        boolean stale=row==null || row.checkedAt==null || row.lastError!=null || invalidCache
                || row.checkedAt.isBefore(BusinessTime.now().minusMinutes(15));
        String message=row!=null && row.lastError!=null?row.lastError:
                row==null || row.checkedAt==null?"尚未查询官方余额，请点击刷新":
                invalidCache?"上次余额数据不可读，请重新刷新":stale?"上次查询结果已过期，请刷新":"供应商账户余额，同一账户下多个 Key 的余额不能相加";
        return new BalanceResponse(true,row==null?null:row.available,stale,row==null?null:row.checkedAt,
                row==null?null:row.attemptedAt,message,console,amounts);
    }
    private BigDecimal amount(JsonNode value,String field) {
        String text=value.path(field).asText();
        if(text.length()>64) throw new IllegalArgumentException();
        return new BigDecimal(text);
    }
    private AiConfigurationRow required(long id) {
        AiConfigurationRow row=mapper.latest(id);
        if(row==null) throw new BusinessException(ErrorCode.NOT_FOUND,"模型配置不存在");
        return row;
    }
}
