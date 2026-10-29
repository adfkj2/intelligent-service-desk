-- V5：事务性 Outbox + 站内信
--
-- 要解决的问题：工单状态变更后要通知创建者/处理人。如果直接在 Service 里发消息
-- （写通知表、发邮件、推 MQ），一旦通知写入失败，要么整个业务回滚（因为通知挂了而不能改工单），
-- 要么用 try-catch 吞掉（业务成功但通知丢了，谁也发现不了）。
--
-- Outbox 的解法：把"要发的事件"当成业务数据，和工单变更写在同一个事务里；
-- 再由独立的调度器把 PENDING 事件投递出去。业务与通知因此解耦，且不会丢。
CREATE TABLE IF NOT EXISTS outbox_event (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    event_type     VARCHAR(64)  NOT NULL COMMENT '事件类型，如 TICKET_STATUS_CHANGED',
    aggregate_type VARCHAR(32)  NOT NULL COMMENT '聚合类型，如 TICKET',
    aggregate_id   BIGINT       NOT NULL COMMENT '聚合 ID（工单 ID）',
    payload        TEXT         NOT NULL COMMENT '事件快照（JSON），消费者不需要回查业务表',
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED',
    attempts       INT          NOT NULL DEFAULT 0 COMMENT '已投递尝试次数',
    last_error     VARCHAR(255) NULL     COMMENT '最后一次失败原因（截断）',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at        DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_status_created (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '事务性发件箱';

CREATE TABLE IF NOT EXISTS notification (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT       NOT NULL COMMENT '接收人',
    ticket_id  BIGINT       NULL     COMMENT '关联工单',
    type       VARCHAR(32)  NOT NULL COMMENT 'TICKET_STATUS_CHANGED / SLA_BREACH',
    title      VARCHAR(128) NOT NULL,
    content    VARCHAR(500) NOT NULL,
    read_flag  TINYINT      NOT NULL DEFAULT 0 COMMENT '0 未读 / 1 已读',
    event_id   BIGINT       NULL     COMMENT '来源 outbox 事件，用于重试幂等',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 关键：调度器重试时同一事件不能给同一个人发第二遍
    UNIQUE KEY uk_event_user (event_id, user_id),
    KEY idx_user_read (user_id, read_flag, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '站内信';
