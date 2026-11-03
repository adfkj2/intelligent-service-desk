package com.isd.ai.admin;

import com.isd.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiEndpointPolicyTest {
    @Test void permitsOfficialProvidersAndExplicitCustomOrigins() {
        AiEndpointPolicy policy=new AiEndpointPolicy("https://models.example.com,https://gateway.example.com:8443");
        assertThat(policy.validate("DEEPSEEK","https://api.deepseek.com/")).isEqualTo("https://api.deepseek.com");
        assertThat(policy.validate("OPENAI","https://api.openai.com/v1")).endsWith("/v1");
        assertThat(policy.validate("QWEN","https://dashscope.aliyuncs.com/compatible-mode/v1")).contains("compatible-mode");
        assertThat(policy.validate("QWEN","https://space-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1")).contains("space-123");
        assertThat(policy.validate("QWEN","https://dashscope-intl.aliyuncs.com/compatible-mode/v1")).contains("dashscope-intl");
        assertThat(policy.validate("QWEN","https://dashscope-us.aliyuncs.com/compatible-mode/v1")).contains("dashscope-us");
        assertThat(policy.validate("CUSTOM","https://models.example.com/v1///")).isEqualTo("https://models.example.com/v1");
        assertThat(policy.validate("CUSTOM","https://gateway.example.com:8443/v1")).contains(":8443/v1");
    }
    @Test void refusesCredentialLeaksRedirectPathsAndUnapprovedOrigins() {
        AiEndpointPolicy policy=new AiEndpointPolicy("");
        for(String url:new String[]{"http://api.deepseek.com","https://user:pass@api.deepseek.com", "https://api.deepseek.com/?key=x",
                "https://api.deepseek.com/#fragment","https://api.deepseek.com./","https://api.deepseek.com/a/../v1",
                "https://api.deepseek.com/%2e%2e/v1","https://api.deepseek.com:0","https://api.deepseek.com:99999",
                "https://api.deepseek.com:8443","https://evil.example/v1","https://api.deepseek.com/chat/completions"}) {
            assertThatThrownBy(()->policy.validate("DEEPSEEK",url)).as(url).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(()->policy.validate("CUSTOM","https://api.deepseek.com")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->policy.validate("UNKNOWN","https://api.deepseek.com")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->new AiEndpointPolicy("https://models.example.com/v1")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->new AiEndpointPolicy("http://models.example.com")).isInstanceOf(BusinessException.class);
    }
}
