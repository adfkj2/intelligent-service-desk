package com.isd.ai.controller;

import com.isd.ai.dto.ChatAnswerResponse;
import com.isd.ai.dto.ChatRequest;
import com.isd.ai.dto.AnswerFeedbackRequest;
import com.isd.ai.dto.AiMetricsResponse;
import com.isd.ai.dto.ConversationResponse;
import com.isd.ai.dto.DraftRequest;
import com.isd.ai.dto.TicketDraftResponse;
import com.isd.ai.dto.TicketAssistResponse;
import com.isd.ai.service.AiRateLimiter;
import com.isd.ai.service.AiWorkspaceService;
import com.isd.auth.security.CurrentUserProvider;
import com.isd.common.result.ApiResponse;
import com.isd.common.web.ClientIp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * AI 助手接口。
 * 对话、可编辑建议与反馈；模型没有任何工单写入工具。
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI 助手", description = "基于当前用户工单与知识库的只读问答")
public class AiController {

    private final AiRateLimiter aiRateLimiter;
    private final CurrentUserProvider currentUserProvider;
    private final AiWorkspaceService workspaceService;

    public AiController(AiRateLimiter aiRateLimiter,
                        CurrentUserProvider currentUserProvider,
                        AiWorkspaceService workspaceService) {
        this.aiRateLimiter = aiRateLimiter;
        this.currentUserProvider = currentUserProvider;
        this.workspaceService = workspaceService;
    }

    /**
     * 配额检查放在最前面：被拒绝的请求不能进入检索与模型调用，
     * 否则限流只挡住了响应，没挡住成本。
     */
    @PostMapping("/chat")
    @Operation(summary = "对话",
            description = "只读：可查当前用户的工单、检索知识库；不执行任何写操作。"
                    + "source=FALLBACK 表示模型不可用，已将知识库检索结果作为兜底答案返回。"
                    + "同一用户或同一来源 IP 超过配额返回 429（业务码 42900）。")
    public ApiResponse<ChatAnswerResponse> chat(@Valid @RequestBody ChatRequest request,
                                                HttpServletRequest httpRequest) {
        aiRateLimiter.check(currentUserProvider.requireId(), ClientIp.of(httpRequest));
        return ApiResponse.ok(workspaceService.chat(request));
    }

    @PostMapping("/draft")
    @Operation(summary = "生成可编辑工单草稿，不提交工单")
    public ApiResponse<TicketDraftResponse> draft(@Valid @RequestBody DraftRequest request,
                                                 HttpServletRequest httpRequest) {
        aiRateLimiter.check(currentUserProvider.requireId(), ClientIp.of(httpRequest));
        return ApiResponse.ok(workspaceService.draft(request));
    }

    @PostMapping("/tickets/{id}/assist")
    @Operation(summary = "处理助手：摘要、相似案例、排查建议和回复草稿")
    public ApiResponse<TicketAssistResponse> assist(@PathVariable Long id, HttpServletRequest httpRequest) {
        aiRateLimiter.check(currentUserProvider.requireId(), ClientIp.of(httpRequest));
        return ApiResponse.ok(workspaceService.assist(id));
    }

    @GetMapping("/conversations/{id}")
    @Operation(summary = "读取本人会话最近十条消息")
    public ApiResponse<ConversationResponse> conversation(@PathVariable String id) {
        return ApiResponse.ok(workspaceService.conversation(id));
    }

    @PostMapping("/answers/{id}/feedback")
    @Operation(summary = "反馈本人回答的帮助、解决和采纳情况")
    public ApiResponse<Void> feedback(@PathVariable Long id, @RequestBody AnswerFeedbackRequest request) {
        workspaceService.feedback(id, request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/metrics")
    @Operation(summary = "技术支持与管理员查看 AI 反馈汇总")
    public ApiResponse<AiMetricsResponse> metrics() {
        return ApiResponse.ok(workspaceService.metrics());
    }
}
