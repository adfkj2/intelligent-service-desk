SHOW TABLES;
DESC ticket;
SHOW INDEX FROM ticket;   -- 应看到 uk_ticket_no、idx_creator、idx_assignee、idx_status_created
