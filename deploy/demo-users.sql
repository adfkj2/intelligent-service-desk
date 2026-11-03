-- Local demo only. Recheck under a short write lock so a concurrent first
-- registration cannot turn an empty-database bootstrap into a nonempty one.
SET NAMES utf8mb4;
LOCK TABLES sys_user WRITE;
SET @isd_demo_empty = (SELECT COUNT(*) = 0 FROM sys_user);

INSERT IGNORE INTO sys_user
    (username, password_hash, full_name, role, status, must_change_password, version)
SELECT username, password_hash, full_name, role, 1, 0, 0 FROM (
    SELECT 'demo_emp' AS username,
           '$2a$10$HubxZz4BHgRFfYnuJSmE0OOBPzQkXLSjARyUkZmqw.UcPVBfcVhPG' AS password_hash,
           'Demo employee' AS full_name, 'EMPLOYEE' AS role
    UNION ALL
    SELECT 'demo_sup', '$2a$10$XWGBYDz4ojVaH3QjKSWndeMjAYHV6oM1T7IBs7RLIGr7iShvB8kO.', 'Demo support', 'SUPPORT'
    UNION ALL
    SELECT 'demo_admin', '$2a$10$3G4yB.gIoGckORTlvj1yIeGSPU5h19969K6PiO4wsbM.0OylMogSC', 'Demo administrator', 'ADMIN'
) AS demo WHERE @isd_demo_empty = 1;

UNLOCK TABLES;
SELECT IF(@isd_demo_empty, 'CREATED', 'SKIPPED');
