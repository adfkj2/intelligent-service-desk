package com.isd.ai.admin;

import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Credentials may only be sent to official endpoints or operator-approved custom origins. */
@Component
public class AiEndpointPolicy {
    private final Set<String> customOrigins;
    public AiEndpointPolicy(@Value("${isd.ai.allowed-origins:${ISD_AI_ALLOWED_ORIGINS:}}") String allowed) {
        Set<String> values=new HashSet<>();
        for (String entry:allowed.split(",")) {
            if (!entry.isBlank()) {
                URI uri=parse(entry.trim());
                if (!(uri.getPath()==null || uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
                    throw invalid("允许的模型地址应只包含协议、域名和端口");
                }
                values.add(origin(uri));
            }
        }
        this.customOrigins=Set.copyOf(values);
    }
    public String validate(String provider,String baseUrl) {
        URI uri=parse(baseUrl.trim());
        String host=uri.getHost().toLowerCase(Locale.ROOT);
        boolean standardPort=uri.getPort()==-1 || uri.getPort()==443;
        boolean allowed=switch(provider) {
            case "DEEPSEEK" -> standardPort && host.equals("api.deepseek.com");
            case "OPENAI" -> standardPort && host.equals("api.openai.com");
            case "QWEN" -> standardPort && (host.equals("dashscope.aliyuncs.com")
                    || host.equals("dashscope-intl.aliyuncs.com") || host.equals("dashscope-us.aliyuncs.com")
                    || host.matches("[a-zA-Z0-9-]+\\.(cn-beijing|cn-shanghai|ap-southeast-1|us-east-1|eu-central-1)\\.maas\\.aliyuncs\\.com"));
            case "CUSTOM" -> customOrigins.contains(origin(uri));
            default -> false;
        };
        if (!allowed) throw invalid("模型服务地址未获允许，请使用对应官方域名，或由部署方设置 ISD_AI_ALLOWED_ORIGINS");
        String result=uri.toASCIIString();
        while(result.endsWith("/")) result=result.substring(0,result.length()-1);
        if (result.endsWith("/chat/completions")) throw invalid("接口地址应填写 Base URL，不包含 /chat/completions");
        return result;
    }
    private URI parse(String value) {
        try {
            URI uri=URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null
                    || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || uri.getHost().endsWith(".") || uri.getRawPath().contains("..")
                    || uri.getRawPath().contains("%") || uri.getPort()==0 || uri.getPort()>65535) throw new IllegalArgumentException();
            return uri;
        } catch (Exception ex) { throw invalid("接口地址必须为 HTTPS，且不能包含账号、查询参数、片段或路径跳转"); }
    }
    private String origin(URI uri) {
        return "https://"+uri.getHost().toLowerCase(Locale.ROOT)+(uri.getPort()==-1 || uri.getPort()==443?"":":"+uri.getPort());
    }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_PARAM,message); }
}
