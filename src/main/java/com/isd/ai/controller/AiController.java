package com.isd.ai.controller;

import com.isd.ai.dto.ChatAnswerResponse;
import com.isd.ai.dto.ChatRequest;
import com.isd.ai.service.AgentService;
import com.isd.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    public AiController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/chat")
    @Operation(summary = "对话",
            description = "只读：可查当前用户的工单、检索知识库；不执行任何写操作。"
                    + "source=FALLBACK 表示模型不可用，已将知识库检索结果作为兜底答案返回。")
    public ApiResponse<ChatAnswerResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(agentService.chat(request.question()));
    }
}
