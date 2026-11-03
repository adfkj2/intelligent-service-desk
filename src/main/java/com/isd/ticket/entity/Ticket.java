package com.isd.ticket.entity;

import java.time.LocalDateTime;

/**
 * 工单实体，与 ticket 表一一对应。
 * 注意：实体只用于数据访问层，不直接作为接口出参（P2 会引入 VO）。
 */
public class Ticket {

    private Long id;
    private String ticketNo;
    private String title;
    private String description;
    private String category;
    private String priority;
    private String status;
    private Long creatorId;
    /** 客户端幂等键；为空表示这次创建没有启用幂等（历史行为） */
    private String idempotencyKey;
    private Long assigneeId;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String creatorName;
    private String assigneeName;
    private LocalDateTime firstResponseAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime responseDueAt;
    private LocalDateTime resolutionDueAt;
    private LocalDateTime waitingSince;
    private Long pausedSeconds;
    private Integer reopenCount;
    private Integer fillDurationSeconds;

    public String getCreatorName() { return creatorName; }
    public void setCreatorName(String value) { this.creatorName = value; }
    public String getAssigneeName() { return assigneeName; }
    public void setAssigneeName(String value) { this.assigneeName = value; }
    public LocalDateTime getFirstResponseAt() { return firstResponseAt; }
    public void setFirstResponseAt(LocalDateTime value) { this.firstResponseAt = value; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime value) { this.resolvedAt = value; }
    public LocalDateTime getResponseDueAt() { return responseDueAt; }
    public void setResponseDueAt(LocalDateTime value) { this.responseDueAt = value; }
    public LocalDateTime getResolutionDueAt() { return resolutionDueAt; }
    public void setResolutionDueAt(LocalDateTime value) { this.resolutionDueAt = value; }
    public LocalDateTime getWaitingSince() { return waitingSince; }
    public void setWaitingSince(LocalDateTime value) { this.waitingSince = value; }
    public Long getPausedSeconds() { return pausedSeconds; }
    public void setPausedSeconds(Long value) { this.pausedSeconds = value; }
    public Integer getReopenCount() { return reopenCount; }
    public void setReopenCount(Integer value) { this.reopenCount = value; }
    public Integer getFillDurationSeconds() { return fillDurationSeconds; }
    public void setFillDurationSeconds(Integer value) { this.fillDurationSeconds = value; }

    public Long getId() { return id; }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTicketNo() {
        return ticketNo;
    }

    public void setTicketNo(String ticketNo) {
        this.ticketNo = ticketNo;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(Long creatorId) {
        this.creatorId = creatorId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public void setAssigneeId(Long assigneeId) {
        this.assigneeId = assigneeId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
