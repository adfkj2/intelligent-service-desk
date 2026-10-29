-- V2：工单创建幂等键
--
-- 场景：用户点两次「提交」、或前端在超时后自动重试，都会产生两条一模一样的工单。
-- 方案：客户端在 POST /api/tickets 带 Idempotency-Key 头，同一用户 + 同一 key 只落一条。
--
-- 为什么用唯一索引，而不是 Service 里"先查再插"：
-- 先查再插在并发下仍然会双写（两个请求都查不到、都插入）。唯一索引是最终裁判，
-- 冲突时把已有记录返回即可，这也是幂等语义（重复请求得到同一个结果）的正确实现。
--
-- 为什么允许多个 NULL：MySQL 唯一索引对 NULL 不去重，历史数据（没带 key）不受影响；
-- 带了 key 的请求才进入幂等语义，属于向后兼容的增量约束。
ALTER TABLE ticket
    ADD COLUMN idempotency_key VARCHAR(64) NULL COMMENT '客户端幂等键，空表示未启用幂等' AFTER creator_id;

ALTER TABLE ticket
    ADD UNIQUE KEY uk_creator_idempotency (creator_id, idempotency_key);
