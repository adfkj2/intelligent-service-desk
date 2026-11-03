package com.isd.ai.client;

import com.isd.ai.admin.AiConnectionTester;
import com.isd.ai.admin.AiRuntimeSnapshot;
import com.isd.ai.usage.AiUsageService;
import com.isd.auth.security.CurrentUserProvider;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;

@Component
public class TrackedConnectionTester implements AiConnectionTester {
    private final ObjectProvider<SpringAiLlmClient> client;
    private final AiUsageService usage;
    private final CurrentUserProvider users;

    public TrackedConnectionTester(ObjectProvider<SpringAiLlmClient> client, AiUsageService usage, CurrentUserProvider users) {
        this.client = client;
        this.usage = usage;
        this.users = users;
    }

    @Override public void test(AiRuntimeSnapshot snapshot) {
        var invocation = usage.begin(snapshot, users.requireId(), "CONNECTION_TEST");
        try {
            invocation.run(() -> { client.getObject().probe(snapshot); return null; });
            invocation.businessOutcome("SUCCESS");
        } catch (RuntimeException ex) {
            invocation.businessOutcome("ERROR");
            throw new IllegalStateException("连接或工具兼容性测试未通过，请检查 Key、接口地址和模型设置。");
        }
    }
}
