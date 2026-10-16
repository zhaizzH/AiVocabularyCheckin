# S3 摘录：数据库设计（第 7 章）

> 摘自 `docs/S3-软件概要设计说明书-正文.md`，裁剪章节以便子代理上下文注入。
> **权威来源仍是原文档**，冲突时以原文档为准。

---

**7 数据库设计**

**7.1 数据库设计总述**

数据库采用 MySQL 8.0，InnoDB 引擎，全库 `utf8mb4`。核心表共 6 张：用户表、词书表、单词表、用户-词书中间表、学习记录表、错题表。所有表包含公共字段 `id`（BIGINT 自增主键）、`create_time`、`update_time`；用户、学习记录、错题使用逻辑删除（`deleted`），中间表记录物理删除。外键仅在应用层维护，不加数据库级外键约束，但所有关联列建索引。

表清单：

| **表名**        | **说明**                       | **关键索引** |
|-----------------|--------------------------------|--------------|
| user            | 用户表                         | uk_username |
| word_book       | 词书表                         | idx_category |
| word            | 单词表                         | idx_book_id |
| user_word_book  | 用户已选词书中间表             | idx_user_id、idx_book_id |
| study_record    | 学习记录（含打卡）             | idx_user_date、idx_user_book |
| mistake_entry   | 错题表                         | idx_user_id |

**7.2 数据表设计**

**表 7.1 user 用户表**

| **字段**      | **类型**       | **说明**                   |
|---------------|----------------|----------------------------|
| id            | BIGINT         | 主键，自增                 |
| username      | VARCHAR(50)    | 用户名，唯一（微信登录用户可空或自动生成） |
| password_hash | VARCHAR(100)   | BCrypt 密码哈希（微信登录用户为空） |
| openid        | VARCHAR(64)    | 微信 openid，唯一索引，账号密码用户为空 |
| nickname      | VARCHAR(50)    | 昵称                       |
| avatar        | VARCHAR(255)   | 头像 URL                   |
| role          | VARCHAR(20)    | USER / ADMIN               |
| status        | TINYINT        | 1 正常 / 0 禁用            |
| deleted       | TINYINT        | 逻辑删除标志               |
| create_time   | DATETIME       | 创建时间                   |
| update_time   | DATETIME       | 更新时间                   |

**表 7.2 word_book 词书表**

| **字段**      | **类型**       | **说明**                   |
|---------------|----------------|----------------------------|
| id            | BIGINT         | 主键，自增                 |
| name          | VARCHAR(100)   | 词书名                     |
| description   | VARCHAR(500)   | 词书简介                   |
| category      | VARCHAR(20)    | 分类：四级 / 六级 / 考研   |
| word_count    | INT            | 单词数（维护时更新）       |
| deleted       | TINYINT        | 逻辑删除标志               |
| create_time   | DATETIME       | 创建时间                   |
| update_time   | DATETIME       | 更新时间                   |

**表 7.3 word 单词表**

| **字段**      | **类型**       | **说明**                   |
|---------------|----------------|----------------------------|
| id            | BIGINT         | 主键，自增                 |
| book_id       | BIGINT         | 所属词书 id                |
| word          | VARCHAR(100)   | 单词                       |
| phonetic      | VARCHAR(100)   | 音标                       |
| translation   | VARCHAR(255)   | 中文释义                   |
| definition    | VARCHAR(500)   | 英文释义                   |
| example       | VARCHAR(500)   | 示例例句                   |
| audio_url     | VARCHAR(255)   | 发音音频 URL               |
| deleted       | TINYINT        | 逻辑删除标志               |
| create_time   | DATETIME       | 创建时间                   |
| update_time   | DATETIME       | 更新时间                   |

**表 7.4 user_word_book 用户已选词书中间表**

| **字段**      | **类型**       | **说明**                   |
|---------------|----------------|----------------------------|
| id            | BIGINT         | 主键，自增                 |
| user_id       | BIGINT         | 用户 id                    |
| book_id       | BIGINT         | 词书 id                    |
| create_time   | DATETIME       | 创建时间                   |
| update_time   | DATETIME       | 更新时间                   |

（中间表记录物理删除，不加 `deleted` 字段。）

**表 7.5 study_record 学习记录表**

| **字段**         | **类型**       | **说明**                        |
|------------------|----------------|---------------------------------|
| id               | BIGINT         | 主键，自增                      |
| user_id          | BIGINT         | 用户 id                         |
| book_id          | BIGINT         | 词书 id                         |
| word_id          | BIGINT         | 单词 id                         |
| study_type       | VARCHAR(20)    | STUDY 学习 / REVIEW 复习        |
| study_date       | DATE           | 学习日期                        |
| check_in_date    | DATE           | 打卡日期（打卡当日写入）        |
| streak_days      | INT            | 当日连续打卡天数快照            |
| correct          | TINYINT        | 0 答错 / 1 答对（复习时使用）   |
| deleted          | TINYINT        | 逻辑删除标志                    |
| create_time      | DATETIME       | 创建时间                        |
| update_time      | DATETIME       | 更新时间                        |

（打卡热力图按 `check_in_date` 聚合；连续打卡天数由 `StudyService` 计算后写入。）

**表 7.6 mistake_entry 错题表**

| **字段**      | **类型**       | **说明**                   |
|---------------|----------------|----------------------------|
| id            | BIGINT         | 主键，自增                 |
| user_id       | BIGINT         | 用户 id                    |
| word_id       | BIGINT         | 单词 id                    |
| source        | VARCHAR(20)    | 来源：STUDY / REVIEW       |
| wrong_count   | INT            | 累计错误次数               |
| mastered      | TINYINT        | 0 未掌握 / 1 已掌握        |
| deleted       | TINYINT        | 逻辑删除标志               |
| create_time   | DATETIME       | 创建时间                   |
| update_time   | DATETIME       | 更新时间                   |

**7.3 实体关系说明**

1. **user ↔ word_book**：多对多，通过 `user_word_book` 中间表关联（用户可选多本词书）。
2. **word_book ↔ word**：一对多，`word.book_id` 指向 `word_book.id`。
3. **user ↔ word**（经学习记录）：一对多，`study_record.user_id` + `study_record.word_id` 记录每次学习/复习行为。
4. **user ↔ word**（经错题）：一对多，`mistake_entry.user_id` + `mistake_entry.word_id` 记录错题。
5. 关联列（`user_id`、`book_id`、`word_id`）全部建索引；外键关系由应用层维护，不加数据库级外键约束。

---

