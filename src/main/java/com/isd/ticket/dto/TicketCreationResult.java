package com.isd.ticket.dto;

/**
 * 创建工单的结果：除了工单本身，还要告诉调用方这次是"真的新建"还是"幂等重放"。
 *
 * 为什么不在 Controller 里再查一次：判断依据是"插入还是命中已有记录"，只有 Service 知道，
 * 让 Controller 猜会引入一次多余查询，也容易在并发下判断错。
 */
public record TicketCreationResult(TicketResponse ticket, boolean replayed) {
}
