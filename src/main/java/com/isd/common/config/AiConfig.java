package com.isd.common.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.isd.ai.tools.KnowledgeTools;
import com.isd.ai.tools.TicketTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;

/**
 * AI 相关 Bean：线程池 + ChatClient（系统提示词与工具在这里一次性挂上）。
 */
@Configuration
public class AiConfig {

    /**
     * 系统提示词是"只读 Agent"的第一道约束：
     * 明确告诉模型不能编造、不能执行写操作、查不到就如实说。
     * 第二道约束在代码里：工具本身就只有查询能力，模型想写也没有对应工具可调。
     */
    private static final String SYSTEM_PROMPT = """
            你是企业 IT 服务台的智能助手，服务对象是公司员工、技术支持和管理员。
            必须遵守以下规则：
            1. 只能依据工具返回的真实数据回答，绝对不要编造工单号、工单状态、处理人或知识条目。
            2. 你只有查询能力：不能创建、接单、指派、关闭工单。用户要求这类操作时，
               说明你只能查询，并引导他到工单页面操作。
            3. 工具返回空结果时，如实说明"没有查到"，可以建议提交工单，不要猜测。
            4. 涉及工单进展时，先调用 query_my_tickets；涉及具体故障排查时，先调用 search_knowledge_base。
            5. 用简体中文回答，分点说明，控制在 200 字以内。
            """;

    /**
     * LLM 调用线程池。
     *
     * 关键点：把调用丢到另一个线程后，SecurityContext 默认不会跟着过去（ThreadLocal 不继承），
     * 工具里的 CurrentUserProvider 会认为"未登录"。
     * 用 DelegatingSecurityContextExecutorService 包装后，提交任务时会捕获当前上下文、
     * 在任务线程中恢复，工具仍然能以真实登录身份查询。
     */
    @Bean(name = "aiExecutor", destroyMethod = "shutdown")
    public ExecutorService aiExecutor() {
        ExecutorService raw = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "ai-call");
            thread.setDaemon(true);
            return thread;
        });
        return new DelegatingSecurityContextExecutorService(raw);
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 TicketTools ticketTools,
                                 KnowledgeTools knowledgeTools) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(ticketTools, knowledgeTools)
                .build();
    }
}
