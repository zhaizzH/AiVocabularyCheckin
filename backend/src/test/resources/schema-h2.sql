-- =====================================================================
-- 测试用 H2 结构（MySQL 兼容模式）
--
-- 用途：让 `mvn package` 在不依赖本机 MySQL 的前提下完成实体↔表映射验证。
--
-- ⚠️ 本文件是 `resources/db/V001__init.sql` + `V002__word_progress.sql` 的
--    H2 等价翻译（列名、类型、可空性、索引/唯一键**必须保持一致**）。
--    真实 DDL 含 H2 无法解析的 MySQL 专属语法（ENGINE / CHARSET /
--    COLLATE utf8mb4_0900_ai_ci），故不能直接复用。
--
--    **DDL 变更时必须同步修改本文件**，否则测试会在「表结构与生产不一致」
--    的假象下通过，掩盖真实的映射偏差。
--    对真实 MySQL 结构的验证另有 scripts/verify-entities.py（直连真库）。
--
-- 与真实 DDL 的唯一有意差异：
--   * 去掉 ENGINE/CHARSET/COLLATE/COMMENT（H2 不支持，不影响映射语义）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `user` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `username`      VARCHAR(50)  NULL,
  `password_hash` VARCHAR(100) NULL,
  `openid`        VARCHAR(64)  NULL,
  `nickname`      VARCHAR(50)  NULL,
  `avatar`        VARCHAR(255) NULL,
  `role`          VARCHAR(20)  NOT NULL DEFAULT 'USER',
  `status`        TINYINT      NOT NULL DEFAULT 1,
  `deleted`       TINYINT      NOT NULL DEFAULT 0,
  `create_time`   DATETIME     NOT NULL,
  `update_time`   DATETIME     NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uk_username` UNIQUE (`username`),
  CONSTRAINT `uk_openid` UNIQUE (`openid`)
);

CREATE TABLE IF NOT EXISTS `word_book` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(100) NOT NULL,
  `description` VARCHAR(500) NULL,
  `category`    VARCHAR(20)  NOT NULL,
  `word_count`  INT          NOT NULL DEFAULT 0,
  `deleted`     TINYINT      NOT NULL DEFAULT 0,
  `create_time` DATETIME     NOT NULL,
  `update_time` DATETIME     NOT NULL,
  PRIMARY KEY (`id`)
);
CREATE INDEX IF NOT EXISTS `idx_category` ON `word_book` (`category`);

CREATE TABLE IF NOT EXISTS `word` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `book_id`     BIGINT       NOT NULL,
  `word`        VARCHAR(100) NOT NULL,
  `phonetic`    VARCHAR(100) NULL,
  `translation` VARCHAR(255) NULL,
  `definition`  VARCHAR(500) NULL,
  `example`     VARCHAR(500) NULL,
  `audio_url`   VARCHAR(255) NULL,
  `deleted`     TINYINT      NOT NULL DEFAULT 0,
  `create_time` DATETIME     NOT NULL,
  `update_time` DATETIME     NOT NULL,
  PRIMARY KEY (`id`)
);
CREATE INDEX IF NOT EXISTS `idx_book_id` ON `word` (`book_id`);

CREATE TABLE IF NOT EXISTS `user_word_book` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT   NOT NULL,
  `book_id`     BIGINT   NOT NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uk_user_word_book_user_book` UNIQUE (`user_id`, `book_id`)
);
CREATE INDEX IF NOT EXISTS `idx_user_id` ON `user_word_book` (`user_id`);
CREATE INDEX IF NOT EXISTS `idx_book_id` ON `user_word_book` (`book_id`);

CREATE TABLE IF NOT EXISTS `study_record` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT      NOT NULL,
  `book_id`       BIGINT      NOT NULL,
  `word_id`       BIGINT      NOT NULL,
  `study_type`    VARCHAR(20) NOT NULL,
  `study_date`    DATE        NOT NULL,
  `check_in_date` DATE        NULL,
  `streak_days`   INT         NOT NULL DEFAULT 0,
  `correct`       TINYINT     NULL,
  `deleted`       TINYINT     NOT NULL DEFAULT 0,
  `create_time`   DATETIME    NOT NULL,
  `update_time`   DATETIME    NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uk_study_record_user_word_date_type`
    UNIQUE (`user_id`, `word_id`, `study_date`, `study_type`)
);
CREATE INDEX IF NOT EXISTS `idx_user_date` ON `study_record` (`user_id`, `study_date`);
CREATE INDEX IF NOT EXISTS `idx_user_book` ON `study_record` (`user_id`, `book_id`);

CREATE TABLE IF NOT EXISTS `mistake_entry` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT      NOT NULL,
  `word_id`     BIGINT      NOT NULL,
  `source`      VARCHAR(20) NOT NULL,
  `wrong_count` INT         NOT NULL DEFAULT 0,
  `mastered`    TINYINT     NOT NULL DEFAULT 0,
  `deleted`     TINYINT     NOT NULL DEFAULT 0,
  `create_time` DATETIME    NOT NULL,
  `update_time` DATETIME    NOT NULL,
  PRIMARY KEY (`id`)
);
CREATE INDEX IF NOT EXISTS `idx_user_id` ON `mistake_entry` (`user_id`);

CREATE TABLE IF NOT EXISTS `word_progress` (
  `id`               BIGINT   NOT NULL AUTO_INCREMENT,
  `user_id`          BIGINT   NOT NULL,
  `book_id`          BIGINT   NOT NULL,
  `word_id`          BIGINT   NOT NULL,
  `stage`            TINYINT  NOT NULL DEFAULT 0,
  `next_review_date` DATE     NULL,
  `learned`          TINYINT  NOT NULL DEFAULT 0,
  `wrong_count`      INT      NOT NULL DEFAULT 0,
  `deleted`          TINYINT  NOT NULL DEFAULT 0,
  `create_time`      DATETIME NOT NULL,
  `update_time`      DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `uk_word_progress_user_word` UNIQUE (`user_id`, `word_id`)
);
CREATE INDEX IF NOT EXISTS `idx_word_progress_user_review`
  ON `word_progress` (`user_id`, `next_review_date`);
CREATE INDEX IF NOT EXISTS `idx_word_progress_user_book_learned`
  ON `word_progress` (`user_id`, `book_id`, `learned`);
