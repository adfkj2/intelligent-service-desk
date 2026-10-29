package com.isd.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.notification.dto.TicketNotificationPayload;
import com.isd.notification.entity.Notification;
import com.isd.notification.entity.OutboxEvent;
import com.isd.notification.mapper.NotificationMapper;
import com.isd.notification.mapper.OutboxEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把一条 outbox 事件展开成若干条站内信。
 *
 * 幂等的关键在这里：插入 notification 时带上 event_id，表上的 uk_event_user(event_id, user_id)
 * 保证同一事件对同一个人只会落一条。重复消费（多实例抢到同一批、上次提交后进程崩溃）撞到唯一键时
 * 直接跳过，不需要分布式锁，也不需要先查后写（先查后写在并发下本来就有窗口）。
 *
 * 每个事件一个事务：要么"通知全写完 + 事件置 SENT"，要么全部回滚留待重试。
 * 不能把整批事件放进一个事务——一条坏数据会拖累同批的其他事件。
 */
@Service
public class NotificationRelayService {

    private static final Logger log = LoggerFactory.getLogger(NotificationRelayService.class);

    private final OutboxEventMapper outboxEventMapper;
    private final NotificationMapper notificationMapper;
    private final ObjectMapper objectMapper;

    public NotificationRelayService(OutboxEventMapper outboxEventMapper,
                                    NotificationMapper notificationMapper,
                                    ObjectMapper objectMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.notificationMapper = notificationMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 投递单个事件。
     *
     * @return 实际新写入的站内信条数（重复消费时为 0）
     * @throws IllegalStateException payload 无法解析等不可恢复问题，由调度器累计失败次数
     */
    @Transactional
    public int deliver(OutboxEvent event) {
        TicketNotificationPayload payload = parse(event);

        int inserted = 0;
        for (Long userId : payload.recipientIds()) {
            if (userId == null) {
                continue;
            }
            try {
                notificationMapper.insert(toNotification(event, payload, userId));
                inserted++;
            } catch (DuplicateKeyException ignored) {
                // 已经给这个用户发过了：这正是幂等生效的样子，不是错误
                log.debug("站内信重复投递被唯一索引拦下: eventId={}, userId={}", event.getId(), userId);
            }
        }

        // 与上面的插入同事务：不会出现"通知写了但事件仍是 PENDING"（否则会重复扫）,
        // 也不会出现"事件置 SENT 但通知没写"（那就真丢了）
        outboxEventMapper.markSent(event.getId());
        return inserted;
    }

    private TicketNotificationPayload parse(OutboxEvent event) {
        TicketNotificationPayload payload;
        try {
            payload = objectMapper.readValue(event.getPayload(), TicketNotificationPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("outbox 事件 payload 解析失败: id=" + event.getId(), ex);
        }
        if (payload == null || payload.recipientIds() == null) {
            throw new IllegalStateException("outbox 事件 payload 缺少 recipientIds: id=" + event.getId());
        }
        return payload;
    }

    private Notification toNotification(OutboxEvent event, TicketNotificationPayload payload, Long userId) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setTicketId(payload.ticketId());
        notification.setType(event.getEventType());
        notification.setTitle(payload.title());
        notification.setContent(payload.content());
        notification.setEventId(event.getId());
        return notification;
    }
}
