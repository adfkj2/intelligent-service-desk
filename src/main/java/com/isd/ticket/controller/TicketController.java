package com.isd.ticket.controller;

import java.util.List;
import java.util.Locale;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;

import com.isd.auth.security.CurrentUserProvider;
import com.isd.common.result.ApiResponse;
import com.isd.common.result.PageResponse;
import com.isd.ticket.dto.AssignRequest;
import com.isd.ticket.dto.ChangeStatusRequest;
import com.isd.ticket.dto.CreateTicketRequest;
import com.isd.ticket.dto.TicketCreationResult;
import com.isd.ticket.dto.TicketResponse;
import com.isd.ticket.dto.*;
import com.isd.ticket.entity.TicketAttachment;
import com.isd.ticket.entity.TicketOperationLog;
import com.isd.ticket.service.TicketService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

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

    /**
     * 创建工单。
     *
     * 幂等：客户端可以带 {@code Idempotency-Key} 头，同一用户 + 同一 key 重复提交只落一条。
     * 首次创建返回 201；命中幂等重放返回 200 并带 {@code Idempotent-Replay: true}，
     * 让调用方（和日志）能区分"新建成功"和"重复请求被吸收"。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<TicketResponse>> create(
            @Valid @RequestBody CreateTicketRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        TicketCreationResult result = ticketService.create(request, currentUserProvider.require(), idempotencyKey);
        ResponseEntity.BodyBuilder builder = result.replayed()
                ? ResponseEntity.ok()
                : ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            builder.header("Idempotent-Replay", "true");
        }
        return builder.body(ApiResponse.ok(result.ticket()));
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
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo,
            @RequestParam(required = false) String queue) {
        return ApiResponse.ok(ticketService.list(status, category, keyword, page, size,
                currentUserProvider.require(), assigneeId, priority, createdFrom, createdTo, queue));
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
        long count = ticketService.list(status,null,null,1,1,currentUserProvider.require()).total();
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

    @GetMapping("/overview")
    public ApiResponse<TicketOverview> overview() {
        return ApiResponse.ok(ticketService.overview(currentUserProvider.require()));
    }

    @GetMapping("/support-options")
    public ApiResponse<List<SupportOption>> supportOptions() {
        return ApiResponse.ok(ticketService.supportOptions(currentUserProvider.require()));
    }

    @PutMapping("/support-profile")
    public ApiResponse<List<String>> supportProfile(@Valid @RequestBody SupportProfileRequest request) {
        return ApiResponse.ok(ticketService.saveSupportProfile(request.skills(), currentUserProvider.require()));
    }

    @GetMapping("/{id}/assignment-suggestions")
    public ApiResponse<List<AssignmentSuggestion>> suggestions(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.assignmentSuggestions(id,currentUserProvider.require()));
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<TicketResponse> transfer(@PathVariable Long id, @Valid @RequestBody TransferRequest request) {
        return ApiResponse.ok(ticketService.transfer(id,request.assigneeId(),request.reason(),currentUserProvider.require()));
    }

    @GetMapping("/{id}/replies")
    public ApiResponse<List<TicketReplyResponse>> replies(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.replies(id,currentUserProvider.require()));
    }

    @PostMapping("/{id}/replies")
    public ApiResponse<TicketReplyResponse> reply(@PathVariable Long id, @Valid @RequestBody AddReplyRequest request) {
        return ApiResponse.ok(ticketService.addReply(id,request,currentUserProvider.require()));
    }

    @GetMapping("/{id}/attachments")
    public ApiResponse<List<TicketAttachmentResponse>> attachments(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.attachments(id,currentUserProvider.require()));
    }

    @PostMapping(value="/{id}/attachments",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TicketAttachmentResponse> upload(@PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue="PUBLIC") String visibility) {
        return ApiResponse.ok(ticketService.uploadAttachment(id,file,visibility,currentUserProvider.require()));
    }

    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> download(@PathVariable Long id,@PathVariable Long attachmentId) {
        TicketAttachment value = ticketService.downloadAttachment(id,attachmentId,currentUserProvider.require());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
                        .filename(value.getFileName(),StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .header("X-Content-Type-Options","nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(value.getSize()).body(value.getData());
    }

    /** 统计出参：用 record 而不是裸 Map，字段有契约、Swagger 能生成 schema */
    public record StatusCount(String status, long count) {
    }
}
