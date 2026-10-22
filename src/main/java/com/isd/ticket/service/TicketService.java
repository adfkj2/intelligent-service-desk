package com.isd.ticket.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import com.isd.ticket.dto.CreateTicketRequest;
import com.isd.ticket.entity.Ticket;
import com.isd.ticket.mapper.TicketMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String DEFAULT_PRIORITY = "MEDIUM";
    private static final String INITIAL_STATUS = "OPEN";

    private final TicketMapper ticketMapper;

    public TicketService(TicketMapper ticketMapper) {
        this.ticketMapper = ticketMapper;
    }

    /**
     * 创建工单。事务边界在这一层：插入成功后立即回查一次，保证返回给调用方的是含数据库生成值的完整记录。
     */
    @Transactional
    public Ticket create(CreateTicketRequest request, Long creatorId) {
        Ticket ticket = new Ticket();
        ticket.setTicketNo(generateTicketNo());
        ticket.setTitle(request.title());
        ticket.setDescription(request.description());
        ticket.setCategory(request.category());
        ticket.setPriority(request.priority() == null ? DEFAULT_PRIORITY : request.priority());
        ticket.setStatus(INITIAL_STATUS);
        ticket.setCreatorId(creatorId);

        ticketMapper.insert(ticket);

        return ticketMapper.findById(ticket.getId())
                .orElseThrow(() -> new IllegalStateException("工单创建后回查失败，id=" + ticket.getId()));
    }

    @Transactional(readOnly = true)
    public Ticket getById(Long id) {
        return ticketMapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "工单不存在: id=" + id));
    }

    /**
     * 演示用单号：T + 日期 + 4 位随机数。
     * 局限：随机数理论上可能重复，由 uk_ticket_no 唯一索引兜底（插入会失败）；
     * 生产环境应使用数据库序列、雪花算法或 Redis 自增，P2 再讨论。
     */
    private String generateTicketNo() {
        String date = LocalDate.now().format(DATE_FORMAT);
        int suffix = ThreadLocalRandom.current().nextInt(1000, 10000);
        return "T" + date + "-" + suffix;
    }
}
