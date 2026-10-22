package com.isd.ticket.controller;

import com.isd.common.result.ApiResponse;
import com.isd.ticket.dto.CreateTicketRequest;
import com.isd.ticket.entity.Ticket;
import com.isd.ticket.service.TicketService;
import jakarta.validation.Valid;
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
}
