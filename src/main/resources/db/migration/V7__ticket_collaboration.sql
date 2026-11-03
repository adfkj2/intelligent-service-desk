ALTER TABLE ticket
    ADD COLUMN first_response_at DATETIME NULL,
    ADD COLUMN resolved_at DATETIME NULL,
    ADD COLUMN response_due_at DATETIME NULL,
    ADD COLUMN resolution_due_at DATETIME NULL,
    ADD COLUMN waiting_since DATETIME NULL,
    ADD COLUMN paused_seconds BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN reopen_count INT NOT NULL DEFAULT 0,
    ADD COLUMN fill_duration_seconds INT NULL,
    ADD KEY idx_ticket_sla (status, resolution_due_at),
    ADD KEY idx_ticket_assignee_status (assignee_id, status);
ALTER TABLE ticket_operation_log MODIFY COLUMN `comment` VARCHAR(1000) NULL;
UPDATE ticket SET
 response_due_at = DATE_ADD(created_at, INTERVAL CASE priority WHEN 'URGENT' THEN 1 WHEN 'HIGH' THEN 4 WHEN 'LOW' THEN 24 ELSE 8 END HOUR),
 resolution_due_at = DATE_ADD(created_at, INTERVAL CASE priority WHEN 'URGENT' THEN 4 WHEN 'HIGH' THEN 24 WHEN 'LOW' THEN 120 ELSE 72 END HOUR);
UPDATE ticket t SET first_response_at = (
 SELECT MIN(l.created_at) FROM ticket_operation_log l WHERE l.ticket_id=t.id AND l.action='RESOLVE'),
 resolved_at = (SELECT MAX(l.created_at) FROM ticket_operation_log l WHERE l.ticket_id=t.id AND l.action='RESOLVE');

CREATE TABLE ticket_reply (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 ticket_id BIGINT NOT NULL,
 author_id BIGINT NOT NULL,
 content TEXT NOT NULL,
 visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_reply_ticket (ticket_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_attachment (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 ticket_id BIGINT NOT NULL,
 uploader_id BIGINT NOT NULL,
 file_name VARCHAR(255) NOT NULL,
 content_type VARCHAR(128) NOT NULL,
 size BIGINT NOT NULL,
 visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
 data MEDIUMBLOB NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_attachment_ticket (ticket_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_support_profile (
 user_id BIGINT NOT NULL PRIMARY KEY,
 skills VARCHAR(255) NOT NULL DEFAULT '',
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_sla_reminder (
 ticket_id BIGINT NOT NULL,
 reminder_key VARCHAR(128) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (ticket_id,reminder_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
