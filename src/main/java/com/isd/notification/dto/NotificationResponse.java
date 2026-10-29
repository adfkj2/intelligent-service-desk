package com.isd.notification.dto;

import java.time.LocalDateTime;

import com.isd.notification.entity.Notification;

/**
 * 站内信对外视图。
 * 不返回 userId：调用方只可能是本人（查询条件由 Service 用当前登录态强制注入），
 * 暴露出来反而让前端误以为可以指定接收人。
 */
public record NotificationResponse(
        Long id,
        Long ticketId,
        String type,
        String title,
        String content,
        boolean read,
        LocalDateTime createdAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getTicketId(),
                notification.getType(),
                notification.getTitle(),
                notification.getContent(),
                Boolean.TRUE.equals(notification.getReadFlag()),
                notification.getCreatedAt());
    }
}
