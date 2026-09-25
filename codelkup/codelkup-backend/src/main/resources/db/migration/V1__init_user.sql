-- ============================================================
-- V1: 初始化用户表
-- 对应 DEV-DOC-codelkup.md §9.2
-- ============================================================

CREATE TABLE IF NOT EXISTS t_user
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    email       VARCHAR(100) NOT NULL COMMENT '邮箱（登录凭证，唯一）',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密密码',
    nickname    VARCHAR(50)  NOT NULL COMMENT '昵称（可重复，仅展示用）',
    avatar      VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    bio         VARCHAR(255) DEFAULT NULL COMMENT '个人简介',
    tech_tags   VARCHAR(255) DEFAULT NULL COMMENT '技术标签，逗号分隔',
    role        TINYINT      NOT NULL DEFAULT 0 COMMENT '0普通 1版主 2管理员',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1禁言 2封禁',
    reputation  INT          NOT NULL DEFAULT 0 COMMENT '声望值',
    level       TINYINT      NOT NULL DEFAULT 1 COMMENT '等级',
    last_active DATETIME     DEFAULT NULL COMMENT '最后活跃时间',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_email (email),
    KEY idx_nickname (nickname),
    KEY idx_reputation (reputation)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='用户表';
