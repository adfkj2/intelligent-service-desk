package com.isd.ai.controller;

import com.isd.ai.dto.ChatAnswerResponse;
import com.isd.ai.dto.ChatRequest;
import com.isd.ai.service.AgentService;
import com.isd.ai.service.AiRateLimiter;
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

/**
 * AI 助手接口。
 * 只暴露一个对话入口：工具的内部实现不外露，模型是唯一的"路由层"。
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI 助手", description = "基于当前用户工单与知识库的只读问答")
public class AiController {

    private final AgentService agentService;
    private final AiRateLimiter aiRateLimiter;
    private final CurrentUserProvider currentUserProvider;

    public AiController(AgentService agentService,
                        AiRateLimiter aiRateLimiter,
                        CurrentUserProvider currentUserProvider) {
        this.agentService = agentService;
        this.aiRateLimiter = aiRateLimiter;
        this.currentUserProvider = currentUserProvider;
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
        return ApiResponse.ok(agentService.chat(request.question()));
    }
}
