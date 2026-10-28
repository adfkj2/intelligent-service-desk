package com.isd.ai.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.isd.ai.client.LlmClient;
import com.isd.ai.dto.ChatAnswerResponse;
import com.isd.common.metrics.IsdMetrics;
import com.isd.kb.dto.KbItemSummary;
import com.isd.kb.service.KbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI 对话编排：超时控制 + 降级。
 *
 * 三个关键设计：
 * 1. **不做 @Transactional**：LLM 调用是几秒到几十秒的网络 IO，
 *    把它放进事务会长时间占用数据库连接，连接池很快被打空。工单查询由工具自己开只读事务。
 * 2. **硬超时**：外层用有界时间 get()，超时立刻降级，不让用户干等。
 * 3. **降级有内容**：模型不可用时返回知识库关键词检索结果，而不是一句"服务异常"，
 *    业务不因为 AI 挂掉而中断。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String PLACEHOLDER_KEY = "local-placeholder";
    private static final int FALLBACK_LIMIT = 3;

    private final LlmClient llmClient;
    private final KbService kbService;
    private final Executor aiExecutor;
    private final IsdMetrics metrics;
    private final long timeoutSeconds;
    private final String apiKey;

    public AgentService(LlmClient llmClient,
                        KbService kbService,
                        @Qualifier("aiExecutor") Executor aiExecutor,
                        IsdMetrics metrics,
                        @Value("${isd.ai.timeout-seconds:30}") long timeoutSeconds,
                        @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.llmClient = llmClient;
        this.kbService = kbService;
        this.aiExecutor = aiExecutor;
        this.metrics = metrics;
        this.timeoutSeconds = timeoutSeconds;
        this.apiKey = apiKey;
    }

    public ChatAnswerResponse chat(String question) {
        // 计时覆盖到降级检索为止：用户感知的是端到端时间，不只是模型那一跳
        long startNanos = System.nanoTime();

        if (!isConfigured()) {
            return finish(IsdMetrics.AI_NOT_CONFIGURED, startNanos,
                    fallback(question, "AI 未配置 API Key，当前处于降级模式"));
        }

        try {
            // 放到独立线程池执行，主线程只等 timeoutSeconds 秒；超时后任务继续跑完但结果被丢弃
            String answer = CompletableFuture
                    .supplyAsync(() -> llmClient.chat(question), aiExecutor)
                    .get(timeoutSeconds, TimeUnit.SECONDS);

            if (answer == null || answer.isBlank()) {
                return finish(IsdMetrics.AI_EMPTY, startNanos, fallback(question, "AI 没有返回内容"));
            }
            return finish(IsdMetrics.AI_SUCCESS, startNanos,
                    new ChatAnswerResponse(answer.trim(), "LLM", List.of()));
        } catch (TimeoutException ex) {
            log.warn("LLM 调用超过 {} 秒，降级为知识库检索", timeoutSeconds);
            return finish(IsdMetrics.AI_TIMEOUT, startNanos, fallback(question, "AI 响应超时"));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return finish(IsdMetrics.AI_ERROR, startNanos, fallback(question, "AI 调用被中断"));
        } catch (Exception ex) {
            log.error("LLM 调用失败，降级为知识库检索", ex);
            return finish(IsdMetrics.AI_ERROR, startNanos, fallback(question, "AI 服务暂时不可用"));
        }
    }

    /** 所有出口统一收口：每个分支必须记一次，也只记一次 */
    private ChatAnswerResponse finish(String result, long startNanos, ChatAnswerResponse response) {
        metrics.recordAiChat(result, startNanos);
        return response;
    }

    private boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !PLACEHOLDER_KEY.equals(apiKey);
    }

    /**
     * 降级：用用户原话去知识库做关键词检索。
     * 局限：整句当关键词，只有包含原句片段的条目才会命中；
     * 更聪明的做法是先用规则切词（去停用词）再检索，或者干脆用 embedding 做语义召回（v2）。
     */
    private ChatAnswerResponse fallback(String question, String reason) {
        List<String> titles;
        try {
            titles = kbService.search(question, 1, FALLBACK_LIMIT).items()
                    .stream()
                    .map(KbItemSummary::title)
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("降级检索知识库也失败: {}", ex.getMessage());
            titles = List.of();
        }

        String answer = titles.isEmpty()
                ? reason + "，知识库里也没有匹配的条目，建议直接提交工单给技术支持。"
                : reason + "。先给你知识库里的相关条目：" + String.join("；", titles);
        return new ChatAnswerResponse(answer, "FALLBACK", titles);
    }
}
