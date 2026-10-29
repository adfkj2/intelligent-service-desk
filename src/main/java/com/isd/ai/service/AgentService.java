package com.isd.ai.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.isd.ai.client.LlmClient;
import com.isd.ai.dto.ChatAnswerResponse;
import com.isd.ai.entity.AiChatLog;
import com.isd.ai.tools.ToolCallContext;
import com.isd.auth.security.CurrentUserProvider;
import com.isd.common.metrics.IsdMetrics;
import com.isd.common.web.TraceIdFilter;
import com.isd.kb.entity.KbItem;
import com.isd.kb.service.KbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI 对话编排：检索增强（RAG）+ 超时控制 + 降级 + 审计。
 *
 * 四个关键设计：
 * 1. **不做 @Transactional**：LLM 调用是几秒到几十秒的网络 IO，
 *    把它放进事务会长时间占用数据库连接，连接池很快被打空。
 * 2. **先检索再问模型**：把知识库 top-3 作为上下文拼进提示词（RAG 的 R），
 *    模型不必"猜"内部术语，也降低了凭空编造的概率；工具调用仍然保留，用于按需查工单。
 * 3. **硬超时 + 有内容的降级**：超时立刻返回知识库条目，而不是一句"服务异常"。
 * 4. **审计旁路**：每次问答落一条 ai_chat_log，写入失败只 WARN，绝不影响回答。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private static final String PLACEHOLDER_KEY = "local-placeholder";
    /** 注入上下文的条目数：太多会稀释提示词，也会让模型在无关条目上跑偏 */
    private static final int CONTEXT_LIMIT = 3;
    /** 单条知识正文截断：一条 5KB 的条目就能吃掉上下文窗口的大半 */
    private static final int CONTEXT_SNIPPET_CHARS = 400;
    private static final int QUESTION_MAX_CHARS = 500;

    private final LlmClient llmClient;
    private final KbService kbService;
    private final AiChatAuditService auditService;
    private final CurrentUserProvider currentUserProvider;
    private final Executor aiExecutor;
    private final IsdMetrics metrics;
    private final long timeoutSeconds;
    private final String apiKey;

    public AgentService(LlmClient llmClient,
                        KbService kbService,
                        AiChatAuditService auditService,
                        CurrentUserProvider currentUserProvider,
                        @Qualifier("aiExecutor") Executor aiExecutor,
                        IsdMetrics metrics,
                        @Value("${isd.ai.timeout-seconds:30}") long timeoutSeconds,
                        @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.llmClient = llmClient;
        this.kbService = kbService;
        this.auditService = auditService;
        this.currentUserProvider = currentUserProvider;
        this.aiExecutor = aiExecutor;
        this.metrics = metrics;
        this.timeoutSeconds = timeoutSeconds;
        this.apiKey = apiKey;
    }

    public ChatAnswerResponse chat(String question) {
        // 计时覆盖检索 + 模型 + 降级：用户感知的是端到端时间，不只是模型那一跳
        long startNanos = System.nanoTime();

        List<KbItem> hits = retrieve(question);
        List<String> titles = hits.stream().map(KbItem::getTitle).toList();

        if (!isConfigured()) {
            return finish(IsdMetrics.AI_NOT_CONFIGURED, startNanos,
                    fallback(hits, "AI 未配置 API Key，当前处于降级模式"),
                    question, hits, 0, false);
        }

        String prompt = buildPrompt(question, hits);
        AtomicInteger toolCalls = new AtomicInteger();
        try {
            // 放到独立线程池执行，主线程只等 timeoutSeconds 秒；超时后任务继续跑完但结果被丢弃
            String answer = CompletableFuture
                    .supplyAsync(() -> invokeLlm(prompt, toolCalls), aiExecutor)
                    .get(timeoutSeconds, TimeUnit.SECONDS);

            if (answer == null || answer.isBlank()) {
                return finish(IsdMetrics.AI_EMPTY, startNanos,
                        fallback(hits, "AI 没有返回内容"), question, hits, toolCalls.get(), false);
            }
            // 模型正常回答时也把检索命中的条目带上：用户想深挖时能直接去知识库
            return finish(IsdMetrics.AI_SUCCESS, startNanos,
                    new ChatAnswerResponse(answer.trim(), "LLM", titles),
                    question, hits, toolCalls.get(), true);
        } catch (TimeoutException ex) {
            log.warn("LLM 调用超过 {} 秒，降级为知识库检索", timeoutSeconds);
            return finish(IsdMetrics.AI_TIMEOUT, startNanos,
                    fallback(hits, "AI 响应超时"), question, hits, toolCalls.get(), false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return finish(IsdMetrics.AI_ERROR, startNanos,
                    fallback(hits, "AI 调用被中断"), question, hits, toolCalls.get(), false);
        } catch (Exception ex) {
            log.error("LLM 调用失败，降级为知识库检索", ex);
            return finish(IsdMetrics.AI_ERROR, startNanos,
                    fallback(hits, "AI 服务暂时不可用"), question, hits, toolCalls.get(), false);
        }
    }

    /** 所有出口统一收口：每个分支必须记一次指标、也落一条审计 */
    private ChatAnswerResponse finish(String result, long startNanos, ChatAnswerResponse response,
                                      String question, List<KbItem> hits, int toolCalls, boolean success) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        metrics.recordAiChat(result, startNanos);
        audit(question, response, hits.size(), toolCalls, durationMs, success);
        return response;
    }

    private void audit(String question, ChatAnswerResponse response,
                       int kbHits, int toolCalls, long durationMs, boolean success) {
        Long userId = currentUserId();
        if (userId == null) {
            // 理论上到不了这里（接口要求登录）；真到了也不写一条没有归属的审计
            log.debug("当前请求没有登录用户，跳过 AI 审计");
            return;
        }
        AiChatLog entry = new AiChatLog();
        entry.setTraceId(MDC.get(TraceIdFilter.MDC_KEY));
        entry.setUserId(userId);
        entry.setQuestion(truncate(question, QUESTION_MAX_CHARS));
        entry.setAnswer(response.answer());
        entry.setSource(response.source());
        entry.setKbHits(kbHits);
        entry.setToolCalls(toolCalls);
        entry.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
        entry.setSuccess(success);
        auditService.record(entry);
    }

    private Long currentUserId() {
        try {
            return currentUserProvider.require().getId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 召回。失败时不影响主流程：没有上下文最多是回答差一点，不该让整个 AI 入口 500。
     */
    private List<KbItem> retrieve(String question) {
        try {
            List<KbItem> hits = kbService.retrieve(question, CONTEXT_LIMIT);
            metrics.recordAiRetrieval(hits.isEmpty() ? IsdMetrics.RETRIEVAL_MISS : IsdMetrics.RETRIEVAL_HIT);
            return hits;
        } catch (RuntimeException ex) {
            log.warn("知识库召回失败，本次不注入上下文: {}", ex.getMessage());
            metrics.recordAiRetrieval(IsdMetrics.RETRIEVAL_ERROR);
            return List.of();
        }
    }

    private boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !PLACEHOLDER_KEY.equals(apiKey);
    }

    /**
     * 把召回结果拼进用户消息。
     *
     * 为什么放在 user 消息而不是 system：system 提示词是固定的行为约束，
     * 把动态检索结果塞进去会污染规则；放 user 侧也更容易做"资料不可信"的提示。
     */
    private String buildPrompt(String question, List<KbItem> hits) {
        if (hits.isEmpty()) {
            return question;
        }
        StringBuilder prompt = new StringBuilder(256);
        prompt.append("下面是知识库检索到的参考资料（可能只有部分相关，作答时优先参考，不要照抄原文）：\n");
        for (int i = 0; i < hits.size(); i++) {
            KbItem item = hits.get(i);
            prompt.append('【').append(i + 1).append("】").append(item.getTitle()).append('\n');
            prompt.append(truncate(item.getContent(), CONTEXT_SNIPPET_CHARS)).append('\n');
        }
        prompt.append("\n用户问题：").append(question);
        return prompt.toString();
    }

    private String invokeLlm(String prompt, AtomicInteger toolCalls) {
        ToolCallContext.bind(toolCalls);
        try {
            return llmClient.chat(prompt);
        } finally {
            ToolCallContext.clear();
        }
    }

    /**
     * 降级：用已经召回的条目回答，不再查一次库。
     * 局限：只给标题，不给正文——降级模式下用户要的是"去哪儿看"，不是让系统替他读长文。
     */
    private ChatAnswerResponse fallback(List<KbItem> hits, String reason) {
        List<String> titles = hits.stream().map(KbItem::getTitle).toList();
        String answer = titles.isEmpty()
                ? reason + "，知识库里也没有匹配的条目，建议直接提交工单给技术支持。"
                : reason + "。先给你知识库里的相关条目：" + String.join("；", titles);
        return new ChatAnswerResponse(answer, "FALLBACK", titles);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
