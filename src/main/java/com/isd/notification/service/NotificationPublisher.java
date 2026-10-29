package com.isd.notification.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.auth.security.LoginUser;
import com.isd.notification.dto.TicketNotificationPayload;
import com.isd.notification.entity.OutboxEvent;
import com.isd.notification.mapper.OutboxEventMapper;
import com.isd.ticket.entity.Ticket;
import org.springframework.stereotype.Service;

/**
 * 通知事件的生产者：只写 outbox_event，不直接写 notification。
 *
 * 为什么不在业务里直接发通知：
 * 1) 通知写入失败会把已经成功的业务操作一起回滚，或者被 try-catch 吞掉后静默丢失；
 * 2) 事务提交前发的消息，一旦事务回滚，消息已经出去了，无法撤回（"通知了一条没发生的变更"）。
 * 写进 outbox_event 后，它与业务变更同生共死，投递由调度器异步补上。
 *
 * 注意：本类方法不加 @Transactional。它必须挂在调用方（TicketService）的事务里，
 * 用 REQUIRES_NEW 反而会把事件写在另一个事务，业务回滚时事件还在。
 */
@Service
public class NotificationPublisher {

    /** 工单状态变化（接单 / 解决 / 关闭） */
    public static final String TYPE_TICKET_STATUS_CHANGED = "TICKET_STATUS_CHANGED";
    /** 工单被指派 */
    public static final String TYPE_TICKET_ASSIGNED = "TICKET_ASSIGNED";

    private static final String AGGREGATE_TICKET = "TICKET";
    /** 与 V5 迁移里 notification.title / content 的列长度保持一致 */
    private static final int TITLE_MAX_LENGTH = 128;
    private static final int CONTENT_MAX_LENGTH = 500;

    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;

    public NotificationPublisher(OutboxEventMapper outboxEventMapper, ObjectMapper objectMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 状态变化：通知创建者与当前处理人，操作者本人不通知自己。
     * 两者都没有（例如管理员关单且工单没有处理人）时直接不写事件，省一次无意义的投递。
     */
    public void publishTicketStatusChanged(Ticket ticket, String fromStatus, LoginUser operator) {
        List<Long> recipients = recipients(ticket.getCreatorId(), ticket.getAssigneeId(), operator.getId());
        if (recipients.isEmpty()) {
            return;
        }
        String title = "工单状态变更";
        String content = "工单 " + ticket.getTicketNo() + "《" + ticket.getTitle() + "》已由"
                + statusLabel(fromStatus) + "变更为" + statusLabel(ticket.getStatus())
                + "，操作人：" + displayName(operator);
        publish(TYPE_TICKET_STATUS_CHANGED, ticket.getId(),
                new TicketNotificationPayload(ticket.getId(), truncate(title, TITLE_MAX_LENGTH),
                        truncate(content, CONTENT_MAX_LENGTH), recipients));
    }

    /** 指派：只通知被指派人（创建者在后续状态变化时还会收到通知，不必重复打扰） */
    public void publishTicketAssigned(Ticket ticket, LoginUser operator) {
        if (ticket.getAssigneeId() == null || Objects.equals(ticket.getAssigneeId(), operator.getId())) {
            return;
        }
        String title = "工单已指派给你";
        String content = "工单 " + ticket.getTicketNo() + "《" + ticket.getTitle() + "》已由"
                + displayName(operator) + "指派给你，请及时处理。";
        publish(TYPE_TICKET_ASSIGNED, ticket.getId(),
                new TicketNotificationPayload(ticket.getId(), truncate(title, TITLE_MAX_LENGTH),
                        truncate(content, CONTENT_MAX_LENGTH), List.of(ticket.getAssigneeId())));
    }

    private void publish(String eventType, Long ticketId, TicketNotificationPayload payload) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType(eventType);
        event.setAggregateType(AGGREGATE_TICKET);
        event.setAggregateId(ticketId);
        event.setPayload(toJson(payload));
        event.setStatus(OutboxEvent.STATUS_PENDING);
        outboxEventMapper.insert(event);
    }

    /**
     * 去重 + 排除操作者。用 LinkedHashSet 保持顺序，通知落库顺序稳定，排查日志时好对照。
     */
    private List<Long> recipients(Long creatorId, Long assigneeId, Long operatorId) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (creatorId != null) {
            recipients.add(creatorId);
        }
        if (assigneeId != null) {
            recipients.add(assigneeId);
        }
        recipients.remove(operatorId);
        return new ArrayList<>(recipients);
    }

    /** 用户看到的是中文状态，不是枚举名；未知值原样返回，避免以后加状态时这里抛异常把事件拦下 */
    private String statusLabel(String status) {
        if (status == null) {
            return "未知状态";
        }
        return switch (status) {
            case "OPEN" -> "待接单";
            case "IN_PROGRESS" -> "处理中";
            case "RESOLVED" -> "已解决";
            case "CLOSED" -> "已关闭";
            default -> status;
        };
    }

    private String displayName(LoginUser operator) {
        String fullName = operator.getFullName();
        return fullName == null || fullName.isBlank() ? operator.getUsername() : fullName;
    }

    /**
     * 序列化失败说明 payload 结构本身有问题，属于程序缺陷，必须让业务事务回滚——
     * 否则会出现"工单改了但没有事件"的静默丢失。
     */
    private String toJson(TicketNotificationPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("通知事件序列化失败", ex);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
