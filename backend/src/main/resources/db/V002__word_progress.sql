-- =====================================================================
-- V002__word_progress.sql — 新增第 7 张表 word_progress（单词学习进度）
--
-- 背景：S3 §7 只定义 6 张表，且 §5.4 声明「复习列表为计算生成，不设独立
--       存储表」。但《接口详细设计》§4.1/§4.2 的 stage（间隔阶段）与
--       nextReviewDate 无处存放——study_record 是逐次事件流水，把当前
--       状态塞进去会产生同词多行、字段互相覆盖的脏语义。
--       故新增 word_progress 承载「每用户每单词的当前进度状态」，
--       这是对 S3 的**显式记录偏离**（6 → 7 张表）。
--
-- 说明：本表存的是**进度状态**，不是「今日待复习列表」的快照；
--       待复习列表仍由 `next_review_date <= CURDATE() AND learned = 1`
--       实时计算生成，与 S3 §5.4 的原意不冲突。
--
-- 依据：.trellis/tasks/10-16-backend-api/design.md §3.2
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行。
-- =====================================================================

SET NAMES utf8mb4;

USE `ai_vocab`;

CREATE TABLE IF NOT EXISTS `word_progress` (
  `id`               BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`          BIGINT   NOT NULL COMMENT '用户 id',
  `book_id`          BIGINT   NOT NULL COMMENT '词书 id（冗余，便于按词书聚合）',
  `word_id`          BIGINT   NOT NULL COMMENT '单词 id',
  `stage`            TINYINT  NOT NULL DEFAULT 0 COMMENT '间隔阶段：0=未进入复习，1..4 对应 1/3/7/15 天',
  `next_review_date` DATE     NULL     DEFAULT NULL COMMENT '下次复习日期；NULL=不参与复习',
  `learned`          TINYINT  NOT NULL DEFAULT 0 COMMENT '1=已学过（进入过学习计划并上报）',
  `wrong_count`      INT      NOT NULL DEFAULT 0 COMMENT '累计错误次数（复习调度参考；权威值在 mistake_entry.wrong_count）',
  `deleted`          TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
  `create_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  -- 唯一键 (user_id, word_id)：学习上报天然幂等（INSERT ... ON DUPLICATE KEY UPDATE）
  UNIQUE KEY `uk_word_progress_user_word` (`user_id`, `word_id`),
  -- 「今日待复习」：WHERE user_id=? AND next_review_date <= CURDATE() AND learned=1
  KEY `idx_word_progress_user_review` (`user_id`, `next_review_date`),
  -- 「本词书未学单词」：WHERE user_id=? AND book_id=? AND learned=0
  KEY `idx_word_progress_user_book_learned` (`user_id`, `book_id`, `learned`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = '单词学习进度表（复习间隔 stage / 下次复习日期）';
