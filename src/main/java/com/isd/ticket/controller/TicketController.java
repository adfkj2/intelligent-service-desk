package com.isd.ticket.controller;

import com.isd.common.result.ApiResponse;
import com.isd.ticket.dto.CreateTicketRequest;
import com.isd.ticket.entity.Ticket;
import com.isd.ticket.service.TicketService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Ticket>> create(@Valid @RequestBody CreateTicketRequest request) {
        // TODO(P3)：接入 Spring Security 后改为从 SecurityContext 取当前登录用户，禁止硬编码身份
        Long currentUserId = 1L;
        Ticket created = ticketService.create(request, currentUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    /**
     * 不存在时由 Service 抛 BusinessException，交给 GlobalExceptionHandler 转 404，
     * Controller 只处理正常路径，不再写 Optional 分支。
     */
    @GetMapping("/{id}")
    public ApiResponse<Ticket> getById(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.getById(id));
    }

    /**
     * 注意路径设计：/stats/{status} 是两段，不会和 /{id} 的单段模板冲突。
     * 如果写成 /stats 用查询参数，好处是 URL 语义更"列表化"，坏处是状态值变成了可选项，校验要另写。
     */
    @GetMapping("/stats/{status}")
    public ApiResponse<Map<String, Object>> statsByStatus(@PathVariable String status) {
        long count = ticketService.countByStatus(status);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status.toUpperCase(Locale.ROOT));
        data.put("count", count);
        return ApiResponse.ok(data);
    }
}
