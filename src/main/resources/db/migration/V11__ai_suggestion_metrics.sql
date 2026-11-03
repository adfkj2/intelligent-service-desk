-- Explicit server-side operation kind: ordinary chat must not inflate suggestion adoption metrics.
-- Historical rows stay CHAT because their kind cannot be inferred reliably from model text.
ALTER TABLE ai_chat_log
    ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'CHAT',
    ADD KEY idx_ai_log_purpose (purpose);
