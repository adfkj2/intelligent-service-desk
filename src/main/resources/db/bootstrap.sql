-- ============================================================
-- 只建库，不建表。
-- 表结构由 Flyway 在应用启动时执行 db/migration/V*.sql 创建/升级；
-- 如果 JDBC URL 带了 createDatabaseIfNotExist=true，这一步也可以跳过。
-- ============================================================

CREATE DATABASE IF NOT EXISTS isd CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
