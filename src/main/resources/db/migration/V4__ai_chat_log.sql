-- V4：AI 调用审计
--
-- 为什么要有这张表：模型答错了、答偏了、或者被提示注入带跑了，只靠应用日志无法复盘——
-- 应用日志不会把"模型看到了哪些知识条目""用了几个工具""最终回答是什么"组织在一起。
-- 有了这张表，一次问答可以按 trace_id 串起来，也能按用户/时间查"他到底问过什么"。
--
-- 隐私与容量：问题与回答都是业务内容，属于内部数据；保留策略靠定期任务清理（v1 不做自动清理，
-- README 的已知限制里写明）。question 截断到 500 字符，answer 存全文（TEXT）。
CREATE TABLE IF NOT EXISTS ai_chat_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_id    VARCHAR(64)  NULL     COMMENT '请求 traceId，可与应用日志串联',
    user_id     BIGINT       NOT NULL COMMENT '提问人',
    question    VARCHAR(500) NOT NULL COMMENT '用户问题（超长截断）',
    answer      TEXT         NOT NULL COMMENT '回答正文',
    source      VARCHAR(16)  NOT NULL COMMENT 'LLM（模型回答）/ FALLBACK（降级检索）',
    kb_hits     INT          NOT NULL DEFAULT 0 COMMENT '注入上下文的知识条目数',
    tool_calls  INT          NOT NULL DEFAULT 0 COMMENT '模型发起的工具调用次数',
    duration_ms INT          NOT NULL DEFAULT 0 COMMENT '端到端耗时',
    success     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 拿到模型回答 / 0 降级',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_created (user_id, created_at),
    KEY idx_trace (trace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT 'AI 调用审计';
