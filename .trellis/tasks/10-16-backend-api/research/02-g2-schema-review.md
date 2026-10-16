# 研究记录：G2 闸口复核 —— 表结构与索引调整

> 时间：2026-10-16 · 触发：阶段 3 完成后的 G2 人工闸口，用户选择「先调整表结构/索引再继续」
> 结论：3 项调整已落实并全量实测（列级 + 索引级 + 约束行为 + 幂等 + 红线）。

---

## 1. 用户决策原文

| 问题 | 用户选择 |
|---|---|
| 7 张表 DDL / 索引要调整哪些？ | ① 索引名回退为 S3 原样；② 移除 DB 层时间默认值；③ **按照 `docs/` 下的文档来** |
| 第 7 张表 `word_progress` 是否调整？ | **保持现状** |

第 ③ 项理解为全局校对指令：**以 `docs/` 内文档为唯一权威逐字对齐**。

## 2. 权威来源确认（先查全，避免遗漏）

`docs/` 下 md 文件共 7 个，全部核对：

| 文件 | 是否含表结构定义 |
|---|---|
| `S1-...可行性分析报告-正文.md` | 否 |
| `S2-...软件需求规格说明书-正文.md` | 否（4.4 只泛述「用户表、词书/单词表、学习记录表」） |
| **`S3-软件概要设计说明书-正文.md` §7（L433–L550）** | **是 —— 唯一权威**：6 张表 + 逐字段类型/长度/说明 + 关键索引 |
| `接口详细设计.md` | 否（只定义 HTTP 契约；§8.1 提及单行 JSONL 结构） |
| `需求清单-功能与规则.md` | 否（行为规则；§7 明确不做项） |
| `学生端-需求分析与原型设计.md` | 否（页面/交互） |
| `小程序注册信息-名称简称介绍.md` | 否（运营文案） |

**结论**：S3 §7 是唯一表结构权威，其 §7.1 表清单给出「关键索引」列，
§7.2 表 7.1–7.6 给出逐字段定义。**逐字对齐目标 = 这两处。**

## 3. 三项调整的落实

### 3.1 索引名回退为 S3 §7.1 原样

**改动**（`V001__init.sql`）：

| 表 | 调整前（带表名前缀） | 调整后（S3 原样） |
|---|---|---|
| user | `uk_user_username` | **`uk_username`** |
| user | `uk_user_openid` | **`uk_openid`** |
| word_book | `idx_word_book_category` | **`idx_category`** |
| word | `idx_word_book_id` | **`idx_book_id`** |
| user_word_book | `idx_user_word_book_book_id` | **`idx_book_id`** |
| user_word_book | （缺） | ➕ **`idx_user_id`**（S3 要求，此前遗漏！） |
| study_record | `idx_study_record_user_date` | **`idx_user_date`** |
| study_record | `idx_study_record_user_book` | **`idx_user_book`** |
| mistake_entry | `idx_mistake_entry_user_id` | **`idx_user_id`** |

**发现并修复一处遗漏**：S3 §7.1 明确要求 `user_word_book` 有 `idx_user_id`、`idx_book_id`
**两个**索引，此前我只建了 `idx_book_id`（`user_id` 仅由加固唯一索引的前导列覆盖）。
本次已补建 `idx_user_id`，现为 **3 个索引**（`idx_user_id` / `idx_book_id` /
`uk_user_word_book_user_book`）。

**保持不变**（S3 未定义，加固项无名可对，保留带表名前缀以避歧义）：
`uk_user_word_book_user_book`、`uk_study_record_user_word_date_type`，
以及新增表 `word_progress` 的 3 个索引。

**规范偏差说明**：`database-guidelines.md` 建议 `idx_<表名>_<列名>`，
本次为**交付文档逐字一致性**而有意偏离内部规范（S3 是课设正式交付物，评审会对照文档核查脚本）。
已记入 `design.md §3.4`。

### 3.2 移除 DB 层时间默认值

**改动**（`V001` + `V002`，共 14 个时间列）：

```sql
-- 调整前
`create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
`update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
-- 调整后（裸列，无默认值/无自动更新）
`create_time` DATETIME NOT NULL COMMENT '创建时间（由 MetaObjectHandler 填充）',
`update_time` DATETIME NOT NULL COMMENT '更新时间（由 MetaObjectHandler 填充）',
```

**理由**：消除 ORM 与数据库两套时间戳写入机制的分叉风险。
时间戳单一来源 = MyBatis-Plus `MetaObjectHandler`（阶段 2 已实现 `insertFill`/`updateFill`）。

**传导约束（阶段 4 必须执行）**：7 个实体的 `createTime` / `updateTime` **必须**标注
`@TableField(value = "create_time", fill = FieldFill.INSERT)` /
`@TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)`。
否则插入报 `1364 Field 'create_time' doesn't have a default value`（已实测该报错）。
已记入 `design.md §3.5`。

### 3.3 其余逐字对齐 S3 §7

**逐字段严格比对结果：6 张表（55 列）100% 匹配** ——
列名、类型、长度、可空性、顺序全一致（脚本 `g2_cols.py` 断言 ALL PASS）：

| 表 | 列数 | 结果 |
|---|---|---|
| user | 11 | ✅ 与 S3 表 7.1 逐字一致 |
| word_book | 8 | ✅ 与 S3 表 7.2 逐字一致 |
| word | 11 | ✅ 与 S3 表 7.3 逐字一致 |
| user_word_book | 5 | ✅ 与 S3 表 7.4 逐字一致 |
| study_record | 12 | ✅ 与 S3 表 7.5 逐字一致 |
| mistake_entry | 9 | ✅ 与 S3 表 7.6 逐字一致 |
| word_progress | 11 | （S3 无对应，对 design.md §3.2） |

### 3.4 `word_progress` 保持现状

按用户决定不变更字段集/索引结构，仅同步 3.2 的时间列改动。
（已知设计取舍：`wrong_count` 与 `mistake_entry.wrong_count` 冗余，
后者为权威值 —— 见 design.md §3.2 注。）

## 4. 复核验证证据（全部实测）

| # | 验证项 | 结果 |
|---|---|---|
| 1 | 空库自举（DROP 后跑 V001+V002） | ✅ 7 张表，全 InnoDB / utf8mb4_0900_ai_ci |
| 2 | 列定义 vs S3 逐字段 | ✅ 6 表 55 列 100% 匹配 |
| 3 | 索引 vs S3 §7.1 + design §3.2 | ✅ 14/14 通过（名称+列组合+唯一性），无多余索引 |
| 4 | 时间列无 DB 默认值 | ✅ 14 列 `COLUMN_DEFAULT=NULL`、`EXTRA` 无 `DEFAULT`/`ON UPDATE` |
| 5 | 反证：裸 INSERT 无 `create_time` | ✅ 被拒 `1364`（证明 DB 不再兜底） |
| 6 | 唯一约束：重复 `username` | ✅ `Duplicate entry 'dup' for key 'user.uk_username'` |
| 7 | 唯一索引允许多 NULL `openid` | ✅ 3 行 NULL 均可插入 |
| 8 | 唯一约束：重复选书 | ✅ 被 `uk_user_word_book_user_book` 拦截 |
| 9 | 唯一约束：同日同词同类型学习记录 | ✅ 被 `uk_study_record_user_word_date_type` 拦截 |
| 10 | `word_progress` upsert 幂等 | ✅ 始终 1 行，stage 正确更新为 2 |
| 11 | 脚本幂等（连跑两遍） | ✅ 均 exit 0，仍 7 表 |
| 12 | **红线：`anime_tracker`** | ✅ 20 表、最后建表时间未变（`UNCHANGED: True`） |
| 13 | 构建回归 | ✅ `mvn -o clean package` 37 测试全绿 |
| 14 | 脚本打入 jar 且与磁盘一致 | ✅ `BOOT-INF/classes/db/` 两个文件 md5 相同 |

## 5. 累积到 G6 的契约/文档回写项（更新）

| # | 目标文档 | 内容 | 状态 |
|---|---|---|---|
| 1 | `docs/接口详细设计.md` §0 | 补 `1006/1007/9002/9003/9004` + HTTP 4xx 边界说明 | 待 G6 |
| 2 | `docs/接口详细设计.md` §8/§10 | 补齐 3 个管理端接口 + 变更记录 | 待 G6 |
| 3 | `docs/S3-...-正文.md` §7.1 | 表数 6 → 7（`word_progress`）；`user_word_book` 补加固唯一索引注 | 待同步 |
| 4 | `docs/S3-...-正文.md` §7.5 | `study_record` 加固唯一索引说明 | 待同步 |
| 5 | `docs/S3-...-正文.md` §7.2 | 时间列由 `MetaObjectHandler` 填充（S3 未规定默认值语义，补注即可） | 待同步 |
| 6 | `docs/S3-...-正文.md` §8.2 | 待确定问题登记 AI 供应商为 DeepSeek | 待同步 |
| ~~7~~ | ~~S3 §7.1 索引名~~ | ~~需说明命名补全~~ | ✅ **已消除**（索引名回退为 S3 原样，无需同步） |

## 6. 变更文件

| 文件 | 改动 |
|---|---|
| `backend/src/main/resources/db/V001__init.sql` | 索引名回退 9 处 + 新增 `idx_user_id` + 移除 12 个时间列默认值 |
| `backend/src/main/resources/db/V002__word_progress.sql` | 移除 2 个时间列默认值 |
| `.trellis/tasks/10-16-backend-api/design.md` | 新增 §3.4（索引命名决策）+ §3.5（时间戳填充机制） |
| `.trellis/tasks/10-16-backend-api/implement.md` | 阶段 3 记录更新（G2 复核调整 + 验证结果）+ 待同步文档清单更新 |
| `.trellis/tasks/10-16-backend-api/research/02-g2-schema-review.md` | 本文件 |
