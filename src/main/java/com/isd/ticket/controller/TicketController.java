package com.isd.ticket.controller;

import java.util.List;
import java.util.Locale;

import com.isd.auth.security.CurrentUserProvider;
import com.isd.common.result.ApiResponse;
import com.isd.common.result.PageResponse;
import com.isd.ticket.dto.AssignRequest;
import com.isd.ticket.dto.ChangeStatusRequest;
import com.isd.ticket.dto.CreateTicketRequest;
import com.isd.ticket.dto.TicketResponse;
import com.isd.ticket.entity.TicketOperationLog;
import com.isd.ticket.service.TicketService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单接口。
 * Controller 的职责只有三件：路由、参数校验（@Valid）、取当前身份。
 * 身份一律来自 SecurityContext（CurrentUserProvider），不接受请求体里的 userId。
 */
@RestController
@RequestMapping("/api/tickets")
@Tag(name = "工单", description = "创建 / 分页查询 / 接单 / 指派 / 状态变更 / 操作历史")
public class TicketController {

    private final TicketService ticketService;
    private final CurrentUserProvider currentUserProvider;

    public TicketController(TicketService ticketService, CurrentUserProvider currentUserProvider) {
        this.ticketService = ticketService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TicketResponse>> create(@Valid @RequestBody CreateTicketRequest request) {
        TicketResponse created = ticketService.create(request, currentUserProvider.require());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    /**
     * 分页列表。EMPLOYEE 会被 Service 强制加上 creator_id 过滤，前端传什么参数都绕不过去。
     */
    @GetMapping
    public ApiResponse<PageResponse<TicketResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(ticketService.list(status, category, keyword, page, size,
                currentUserProvider.require()));
    }

    /**
     * 不存在时由 Service 抛 BusinessException，交给 GlobalExceptionHandler 转 404，
     * Controller 只处理正常路径，不再写 Optional 分支。
     */
    @GetMapping("/{id}")
    public ApiResponse<TicketResponse> getById(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.getById(id, currentUserProvider.require()));
    }

    /**
     * 注意路径设计：/stats/{status} 是两段，不会和 /{id} 的单段模板冲突。
     * 如果写成 /stats 用查询参数，好处是 URL 语义更"列表化"，坏处是状态值变成了可选项，校验要另写。
     */
    @GetMapping("/stats/{status}")
    public ApiResponse<StatusCount> statsByStatus(@PathVariable String status) {
        long count = ticketService.countByStatus(status);
        return ApiResponse.ok(new StatusCount(status.toUpperCase(Locale.ROOT), count));
    }

    @PostMapping("/{id}/claim")
    public ApiResponse<TicketResponse> claim(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.claim(id, currentUserProvider.require()));
    }

    @PostMapping("/{id}/assign")
    public ApiResponse<TicketResponse> assign(@PathVariable Long id,
                                              @Valid @RequestBody AssignRequest request) {
        return ApiResponse.ok(ticketService.assign(id, request.assigneeId(), currentUserProvider.require()));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<TicketResponse> changeStatus(@PathVariable Long id,
                                                    @Valid @RequestBody ChangeStatusRequest request) {
        return ApiResponse.ok(ticketService.changeStatus(id, request.action(), request.comment(),
                currentUserProvider.require()));
    }

    @GetMapping("/{id}/operations")
    public ApiResponse<List<TicketOperationLog>> operations(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.operations(id, currentUserProvider.require()));
    }

    /** 统计出参：用 record 而不是裸 Map，字段有契约、Swagger 能生成 schema */
    public record StatusCount(String status, long count) {
    }
}
