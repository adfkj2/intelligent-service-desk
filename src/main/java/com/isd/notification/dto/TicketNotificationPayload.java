package com.isd.notification.dto;

import java.util.List;

/**
 * 工单通知事件的 payload（序列化后存 outbox_event.payload）。
 *
 * 设计取舍：把"收件人 + 文案"在产生事件时就定下来，而不是投递时回查工单表。
 * 回查会读到工单的最新状态——事件积压几分钟后，通知里写的内容可能和事件本身想表达的不一致
 * （例：事件说"已解决"，回查时工单已经 CLOSED）。payload 是事件快照，天然免疫这个问题。
 *
 * 这里只放"投递需要的最小信息"，不放整张工单：payload 越简单，向后兼容越容易。
 *
 * @param ticketId     关联工单，写入 notification.ticket_id
 * @param title        站内信标题（<= 128，由生产者截断）
 * @param content      站内信正文（<= 500，由生产者截断）
 * @param recipientIds 接收人；生产者已去重并排除操作者本人，为空表示无需通知
 */
public record TicketNotificationPayload(
        Long ticketId,
        String title,
        String content,
        List<Long> recipientIds
) {
}
