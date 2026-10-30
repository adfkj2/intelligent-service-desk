-- E2E 种子账号：固定 BCrypt 哈希（10 轮），密码与 README「演示账号」一致。
-- 直接写库而不是走注册接口 + 管理员提权：少两轮跨系统协作，且可重复执行。
--
--   docker compose -f docker-compose.prod.yml exec -T mysql \
--     mysql -uroot -p123456 isd < web/e2e/seed.sql
--
-- 注意：password_hash 是 BCrypt 60 字符（列宽 72），改密码时三处一起换，否则登录会假失败。

INSERT INTO sys_user (username, password_hash, full_name, role, status, must_change_password, version)
VALUES ('demo_emp', '$2a$10$HubxZz4BHgRFfYnuJSmE0OOBPzQkXLSjARyUkZmqw.UcPVBfcVhPG', '演示员工', 'EMPLOYEE', 1, 0, 0),
       ('demo_sup', '$2a$10$XWGBYDz4ojVaH3QjKSWndeMjAYHV6oM1T7IBs7RLIGr7iShvB8kO.', '演示技术支持', 'SUPPORT', 1, 0, 0),
       ('demo_admin', '$2a$10$3G4yB.gIoGckORTlvj1yIeGSPU5h19969K6PiO4wsbM.0OylMogSC', '演示管理员', 'ADMIN', 1, 0, 0)
ON DUPLICATE KEY UPDATE password_hash          = VALUES(password_hash),
                        full_name              = VALUES(full_name),
                        role                   = VALUES(role),
                        status                 = 1,
                        must_change_password   = 0;
