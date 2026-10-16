-- =====================================================================
-- V001__init.sql — 初始化 6 张核心表
--
-- 依据：docs/S3-软件概要设计说明书-正文.md §7（表 7.1 ~ 7.6）
--       .trellis/tasks/10-16-backend-api/design.md §3
--
-- 约定（database-guidelines.md + S3 §7）：
--   * InnoDB / utf8mb4，全库统一；表名、列名一律加反引号。
--   * 公共列：id BIGINT 自增主键、create_time、update_time。
--   * 时间列**不带数据库默认值**：create_time / update_time 一律由
--     MyBatis-Plus MetaObjectHandler 自动填充（单套机制，避免 ORM 与
--     数据库双写时间戳不一致）。因此实体字段必须标 @TableField(fill=...)。
--   * 面向用户数据用逻辑删除（deleted + @TableLogic）；
--     中间表 user_word_book 为物理删除，不加 deleted。
--   * 不加数据库级外键约束，但所有关联列必须建索引。
--   * 索引命名严格照 S3 §7.1「关键索引」列原样（uk_username、idx_category、
--     idx_book_id、idx_user_date、idx_user_book、idx_user_id……），
--     不带表名前缀，以便与设计文档逐字对应。
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可重复执行（课设演示需要）。
--
-- 执行方式：
--   mysql --default-character-set=utf8mb4 < V001__init.sql
--   或在任意 MySQL 客户端中整体执行本文件。
-- =====================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `ai_vocab`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE `ai_vocab`;

-- ---------------------------------------------------------------------
-- 表 7.1 user 用户表
--   * username 唯一索引：微信登录用户 username/password_hash 可空
--     （MySQL 唯一索引允许多个 NULL，语义正确）。
--   * openid 唯一索引：账号密码用户为 NULL。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`      VARCHAR(50)  NULL     DEFAULT NULL COMMENT '用户名，唯一；微信登录用户可空',
  `password_hash` VARCHAR(100) NULL     DEFAULT NULL COMMENT 'BCrypt 密码哈希；微信登录用户为空',
  `openid`        VARCHAR(64)  NULL     DEFAULT NULL COMMENT '微信 openid，唯一；账号密码用户为空',
  `nickname`      VARCHAR(50)  NULL     DEFAULT NULL COMMENT '昵称',
  `avatar`        VARCHAR(255) NULL     DEFAULT NULL COMMENT '头像（一期为内置值 avatar_1~avatar_6）',
  `role`          VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN',
  `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 / 0 禁用',
  `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
  `create_time`   DATETIME     NOT NULL COMMENT '创建时间（由 MetaObjectHandler 填充）',
  `update_time`   DATETIME     NOT NULL COMMENT '更新时间（由 MetaObjectHandler 填充）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_openid` (`openid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表';

-- ---------------------------------------------------------------------
-- 表 7.2 word_book 词书表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `word_book` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        VARCHAR(100) NOT NULL COMMENT '词书名',
  `description` VARCHAR(500) NULL     DEFAULT NULL COMMENT '词书简介',
  `category`    VARCHAR(20)  NOT NULL COMMENT '分类：CET4 / CET6 / KAOYAN',
  `word_count`  INT          NOT NULL DEFAULT 0 COMMENT '单词数（导入/增删时维护）',
  `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
  `create_time` DATETIME     NOT NULL COMMENT '创建时间（由 MetaObjectHandler 填充）',
  `update_time` DATETIME     NOT NULL COMMENT '更新时间（由 MetaObjectHandler 填充）',
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '词书表';

-- ---------------------------------------------------------------------
-- 表 7.3 word 单词表
--   * translation 实测最大 126 字符（考研多义项拼接），VARCHAR(255) 足够。
--   * example 实测最大 200 字符，VARCHAR(500) 足够。
--   * definition / audio_url 一期留空（源数据无英文释义；不做发音播放）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `word` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `book_id`     BIGINT       NOT NULL COMMENT '所属词书 id',
  `word`        VARCHAR(100) NOT NULL COMMENT '单词',
  `phonetic`    VARCHAR(100) NULL     DEFAULT NULL COMMENT '音标（英式 uk）',
  `translation` VARCHAR(255) NULL     DEFAULT NULL COMMENT '中文释义（多义项以「；」连接）',
  `definition`  VARCHAR(500) NULL     DEFAULT NULL COMMENT '英文释义（一期留空）',
  `example`     VARCHAR(500) NULL     DEFAULT NULL COMMENT '示例例句（内置降级例句来源）',
  `audio_url`   VARCHAR(255) NULL     DEFAULT NULL COMMENT '发音音频 URL（一期留空）',
  `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
  `create_time` DATETIME     NOT NULL COMMENT '创建时间（由 MetaObjectHandler 填充）',
  `update_time` DATETIME     NOT NULL COMMENT '更新时间（由 MetaObjectHandler 填充）',
  PRIMARY KEY (`id`),
  KEY `idx_book_id` (`book_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '单词表';

-- ---------------------------------------------------------------------
-- 表 7.4 user_word_book 用户已选词书中间表
--   * 物理删除（移出词书即 DELETE 本行），故不加 deleted。
--   * S3 §7.1 关键索引：idx_user_id、idx_book_id，均按原样建。
--   * 加固：uk_user_word_book_user_book 唯一索引，从数据库层杜绝
--     同一用户重复选同一词书（应用层另返回 2003）。
--     —— 该唯一索引 S3 原文未定义，属刻意加固（design.md §4.2 相邻记录）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_word_book` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT   NOT NULL COMMENT '用户 id',
  `book_id`     BIGINT   NOT NULL COMMENT '词书 id',
  `create_time` DATETIME NOT NULL COMMENT '创建时间（由 MetaObjectHandler 填充）',
  `update_time` DATETIME NOT NULL COMMENT '更新时间（由 MetaObjectHandler 填充）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_word_book_user_book` (`user_id`, `book_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_book_id` (`book_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户已选词书中间表';

-- ---------------------------------------------------------------------
-- 表 7.5 study_record 学习记录表（含打卡）
--   * S3 §7.1 关键索引：idx_user_date、idx_user_book，均按原样建。
--   * 打卡热力图按 check_in_date 聚合；连续打卡天数由 StudyService 计算后写入快照。
--   * 加固：uk_study_record_user_word_date_type 唯一索引，防并发重复打卡产生重复行
--     （S3 原文无此索引，属刻意加固，见 design.md §4.2）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `study_record` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`       BIGINT      NOT NULL COMMENT '用户 id',
  `book_id`       BIGINT      NOT NULL COMMENT '词书 id',
  `word_id`       BIGINT      NOT NULL COMMENT '单词 id',
  `study_type`    VARCHAR(20) NOT NULL COMMENT 'STUDY 学习 / REVIEW 复习',
  `study_date`    DATE        NOT NULL COMMENT '学习日期（热力图与掌握度按此聚合）',
  `check_in_date` DATE        NULL DEFAULT NULL COMMENT '打卡日期（仅打卡当日写入）',
  `streak_days`   INT         NOT NULL DEFAULT 0 COMMENT '当日连续打卡天数快照',
  `correct`       TINYINT     NULL DEFAULT NULL COMMENT '0 答错 / 1 答对（仅复习使用）',
  `deleted`       TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
  `create_time`   DATETIME    NOT NULL COMMENT '创建时间（由 MetaObjectHandler 填充）',
  `update_time`   DATETIME    NOT NULL COMMENT '更新时间（由 MetaObjectHandler 填充）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_study_record_user_word_date_type` (`user_id`, `word_id`, `study_date`, `study_type`),
  KEY `idx_user_date` (`user_id`, `study_date`),
  KEY `idx_user_book` (`user_id`, `book_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '学习记录表（含打卡）';

-- ---------------------------------------------------------------------
-- 表 7.6 mistake_entry 错题表
--   * S3 §7.1 关键索引：idx_user_id，按原样建。
--   * 收录去重由应用层保证（同词重复错 → wrong_count+1，不重复建记录）。
--     此处不加 (user_id, word_id) 唯一索引：逻辑删除的行仍留在表中，
--     被移除后重新收录会与历史行冲突（见 design.md §4.4）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `mistake_entry` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT      NOT NULL COMMENT '用户 id',
  `word_id`     BIGINT      NOT NULL COMMENT '单词 id',
  `source`      VARCHAR(20) NOT NULL COMMENT '来源：STUDY 学习中不认识 / REVIEW 复习答错',
  `wrong_count` INT         NOT NULL DEFAULT 0 COMMENT '累计错误次数（权威值，契约 §5.1 直接返回）',
  `mastered`    TINYINT     NOT NULL DEFAULT 0 COMMENT '0 未掌握 / 1 已掌握',
  `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
  `create_time` DATETIME    NOT NULL COMMENT '创建时间（由 MetaObjectHandler 填充）',
  `update_time` DATETIME    NOT NULL COMMENT '更新时间（由 MetaObjectHandler 填充）',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '错题表';
