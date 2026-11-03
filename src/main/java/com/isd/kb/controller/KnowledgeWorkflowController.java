package com.isd.kb.controller;

import java.util.List;

import com.isd.ai.service.AiRateLimiter;
import com.isd.auth.security.CurrentUserProvider;
import com.isd.common.result.ApiResponse;
import com.isd.common.web.ClientIp;
import com.isd.kb.dto.KbItemResponse;
import com.isd.kb.dto.KnowledgeWorkflow.*;
import com.isd.kb.service.KnowledgeWorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/kb")
public class KnowledgeWorkflowController {
    private final KnowledgeWorkflowService workflow;
    private final CurrentUserProvider users;
    private final AiRateLimiter limiter;
    public KnowledgeWorkflowController(KnowledgeWorkflowService workflow, CurrentUserProvider users, AiRateLimiter limiter) {
        this.workflow = workflow;
        this.users = users;
        this.limiter = limiter;
    }
    @GetMapping("/drafts")
    public ApiResponse<List<Draft>> drafts() { return ApiResponse.ok(workflow.list(users.require())); }
    @PostMapping("/drafts/from-ticket")
    public ApiResponse<Draft> fromTicket(@Valid @RequestBody FromTicket request, HttpServletRequest http) {
        limiter.check(users.requireId(), ClientIp.of(http));
        return ApiResponse.ok(workflow.fromTicket(request, users.require()));
    }
    @PatchMapping("/drafts/{id}")
    public ApiResponse<Draft> edit(@PathVariable Long id, @Valid @RequestBody EditDraft request) {
        return ApiResponse.ok(workflow.edit(id, request, users.require()));
    }
    @PostMapping("/drafts/{id}/publish")
    public ApiResponse<KbItemResponse> publish(@PathVariable Long id, @Valid @RequestBody Publish request) {
        return ApiResponse.ok(workflow.publish(id, request, users.require()));
    }
    @PostMapping("/items/{id}/review")
    public ApiResponse<KbItemResponse> review(@PathVariable Long id, @Valid @RequestBody Review request) {
        return ApiResponse.ok(workflow.review(id, request, users.require()));
    }
    @GetMapping("/items/{id}/feedback")
    public ApiResponse<FeedbackSummary> feedback(@PathVariable Long id) { return ApiResponse.ok(workflow.feedback(id, users.require())); }
    @PostMapping("/items/{id}/feedback")
    public ApiResponse<FeedbackSummary> feedback(@PathVariable Long id, @Valid @RequestBody Feedback request) {
        return ApiResponse.ok(workflow.feedback(id, request, users.require()));
    }
}
