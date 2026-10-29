package com.isd.notification.entity;

import java.time.LocalDateTime;

/**
 * 发件箱事件（表 outbox_event，见 V5）。
 *
 * 写入与业务变更同事务，投递由调度器异步完成；
 * payload 存事件快照，消费者不需要回查业务表（回查会读到更新的状态，导致"通知内容与事件不一致"）。
 */
public class OutboxEvent {

    /** 待投递 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已投递 */
    public static final String STATUS_SENT = "SENT";
    /** 重试耗尽，等待人工介入 */
    public static final String STATUS_FAILED = "FAILED";

    private Long id;
    private String eventType;
    private String aggregateType;
    private Long aggregateId;
    private String payload;
    private String status;
    private Integer attempts;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(Long aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }
}
