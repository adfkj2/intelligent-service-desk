ALTER TABLE kb_item
    ADD COLUMN environment VARCHAR(255) NULL,
    ADD COLUMN review_due_at DATETIME NULL,
    ADD COLUMN reviewed_by BIGINT NULL,
    ADD COLUMN source_ticket_id BIGINT NULL,
    ADD INDEX idx_kb_review_due (review_due_at, id);

CREATE TABLE kb_draft (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_id BIGINT UNSIGNED NOT NULL,
    category_id BIGINT UNSIGNED NULL,
    title VARCHAR(128) NOT NULL,
    content TEXT NOT NULL,
    tags VARCHAR(255) NULL,
    environment VARCHAR(255) NULL,
    review_due_at DATETIME NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT UNSIGNED NOT NULL,
    reviewed_by BIGINT UNSIGNED NULL,
    published_item_id BIGINT UNSIGNED NULL,
    source VARCHAR(16) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_kb_draft_owner (created_by, status, id),
    INDEX idx_kb_draft_ticket (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE kb_feedback (
    item_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    helpful BOOLEAN NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (item_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
