-- V6：数据保留（清理）所需的索引
--
-- 背景：ai_chat_log 与 notification 会随使用无限增长，保留任务按 created_at 分批删除。
-- 现有索引的最左列都不是 created_at（idx_user_created 以 user_id 开头、idx_user_read 以 user_id 开头），
-- 直接按时间删会退化成全表扫描，表一大就把日终任务拖成慢查询。
--
-- outbox_event 不需要新索引：已有 idx_status_created(status, created_at)，
-- 清理条件 status='SENT' AND created_at < ? 正好命中该索引前缀。
--
-- 只用普通二级索引，不锁表（MySQL 8 的 online DDL），对现有数据无侵入。
CREATE INDEX idx_ai_log_created ON ai_chat_log (created_at);

-- read_flag 放最左：清理只删已读，等值匹配后再按时间范围扫描
CREATE INDEX idx_notification_read_created ON notification (read_flag, created_at);
