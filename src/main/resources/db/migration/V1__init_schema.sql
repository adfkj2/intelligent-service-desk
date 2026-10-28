-- ============================================================
-- V1：初始表结构
-- 由 Flyway 在应用启动时执行；库本身要先存在（见 db/bootstrap.sql）。
-- 之后的任何结构变更都新增 V2__xxx.sql，不要改这个文件——
-- 已执行过的脚本一旦修改，校验和会变，Flyway 会直接拒绝启动。
-- ============================================================

-- 用户表：`user` 是 MySQL 保留字，所以加 sys_ 前缀
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(50)     NOT NULL COMMENT '登录名',
    password_hash VARCHAR(72)     NOT NULL COMMENT 'BCrypt 哈希（输出 60 字符，留余量）',
    full_name     VARCHAR(50)     NULL     COMMENT '显示名',
    phone         VARCHAR(20)     NULL     COMMENT '手机号，用于登录页管理员联系方式',
    email         VARCHAR(100)    NULL     COMMENT '邮箱，用于登录页管理员联系方式',
    role          VARCHAR(20)     NOT NULL COMMENT 'EMPLOYEE/SUPPORT/ADMIN',
    status        TINYINT         NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 禁用',
    must_change_password TINYINT  NOT NULL DEFAULT 0 COMMENT '1 下次登录必须改密（管理员重置后置 1）',
    version       INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_role_status (role, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '系统用户';

CREATE TABLE IF NOT EXISTS ticket (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    ticket_no   VARCHAR(32)     NOT NULL COMMENT '对外业务编号',
    title       VARCHAR(128)    NOT NULL COMMENT '标题',
    description TEXT            NULL     COMMENT '描述',
    category    VARCHAR(32)     NOT NULL COMMENT 'IT_FAULT/PERMISSION/DEVICE/OTHER',
    priority    VARCHAR(16)     NOT NULL DEFAULT 'MEDIUM' COMMENT 'LOW/MEDIUM/HIGH/URGENT',
    status      VARCHAR(16)     NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/IN_PROGRESS/RESOLVED/CLOSED',
    creator_id  BIGINT          NOT NULL COMMENT '创建者',
    assignee_id BIGINT          NULL     COMMENT '处理人，未接单时为 NULL',
    version     INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_no (ticket_no),
    KEY idx_creator (creator_id),
    KEY idx_assignee (assignee_id),
    KEY idx_status_created (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '工单';

-- 操作历史 / 审计表：与状态更新同一事务写入
-- 说明：暂不建物理外键，避免单测造数时必须先建用户行；一致性由 Service 层保证。
CREATE TABLE IF NOT EXISTS ticket_operation_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    ticket_id   BIGINT       NOT NULL COMMENT '工单 ID',
    operator_id BIGINT       NOT NULL COMMENT '操作人 ID',
    action      VARCHAR(32)  NOT NULL COMMENT 'CREATE/CLAIM/ASSIGN/RESOLVE/CLOSE',
    from_status VARCHAR(16)  NULL     COMMENT '变更前状态（CREATE 时为 NULL）',
    to_status   VARCHAR(16)  NULL     COMMENT '变更后状态',
    `comment`   VARCHAR(500) NULL     COMMENT '操作备注',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ticket (ticket_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '工单操作历史';

CREATE TABLE IF NOT EXISTS kb_category (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(64)  NOT NULL COMMENT '分类名',
    description VARCHAR(255) NULL     COMMENT '分类说明',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '知识库分类';

-- 知识条目
-- v1 检索用 LIKE '%kw%'：前置通配符让 B+ 树索引失效，全表扫描。
-- v2 方案：FULLTEXT + ngram 分词（MySQL 5.7+ 支持中文三元分词），或用 ES。
CREATE TABLE IF NOT EXISTS kb_item (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    category_id BIGINT       NOT NULL COMMENT '所属分类',
    title       VARCHAR(128) NOT NULL COMMENT '标题',
    content     TEXT         NOT NULL COMMENT '正文',
    tags        VARCHAR(255) NULL     COMMENT '标签，逗号分隔',
    created_by  BIGINT       NOT NULL COMMENT '创建者',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_category (category_id),
    KEY idx_created_by (created_by),
    KEY idx_title (title)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '知识条目';
