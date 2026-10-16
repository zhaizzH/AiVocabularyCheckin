# backend 业务后端 + MySQL 脚本

## Goal

实现 Spring Boot 业务后端，使《接口详细设计》中全部**对外接口**可被两个前端联调，并完成三本真实词书的种子导入。

本子任务是**用户已确认的第一优先项**：后端全部接口先可自测通过（AI 三接口先走降级路径即可），为后续三个子任务提供稳定契约基座。

---

## 前置条件

| 项 | 状态 |
|---|---|
| 数据库 | MySQL 8.4.9 @ `127.0.0.1:3306`，`root` 账号可用 ✅ 已验证 |
| 建库 | `ai_vocab` 库（utf8mb4 / utf8mb4_0900_ai_ci） |
| 词库源文件 | 需先落盘到 `data/vocab-source/`（S3 决策的 `full_line_jsonl/sentence/正序/` 三份） |
| ai-service | **不需要**存在即可完成本任务（AI 走降级路径） |
| 网络 | Maven Central 直连可用 ✅ |

---

## 需求条目（来自文档，逐条可验收）

### 1. 工程骨架与规范

- `backend/` 独立 Maven 工程，包根 `com.vocab`（S3 §3.4 指定）。
- 三层架构，调用方向严格单向 `controller → service → mapper`。
- Controller 不写业务规则、不直接调 Mapper；Service 承载规则并抛 `BizException`。
- 统一 `Result<T>` 包装（`code`/`message`/`data`），由 `@RestControllerAdvice` 兜底转换。
- 实体统一用 `entity/`，对外一律 `dto/` 的 `XxxRequest`/`XxxResponse`，**绝不暴露实体**。
- 除注册/登录/微信登录外，全部接口要求 JWT；`/api/admin/**` 额外校验 ADMIN。
- 分页统一 `page`（默认 1）/ `size`（默认 20），响应 `{ list, total, page, size }`。
- JSON 小驼峰；日期 `YYYY-MM-DD`，时间 `YYYY-MM-DD HH:mm:ss`。

### 2. 数据库（7 张表）

按 S3 §7 定义 6 张表 + 决策新增 `word_progress`：

1. `user` — 含 `openid`（VARCHAR(64)，唯一索引，可空）、`uk_username`
2. `word_book` — `idx_category`，`word_count`
3. `word` — `idx_book_id`，含 `definition` / `audio_url`（一期留空）
4. `user_word_book` — 中间表，物理删除，无 `deleted`，`idx_user_id` / `idx_book_id`
5. `study_record` — `idx_user_date` / `idx_user_book`，含 `check_in_date` / `streak_days` / `correct`
6. `mistake_entry` — `idx_user_id`，含 `wrong_count` / `mastered` / `source`
7. **`word_progress`（新增）** — `user_id` / `book_id` / `word_id` / `stage` / `next_review_date` / `learned`，唯一索引 `(user_id, word_id)`

公共约定：`id` BIGINT 自增主键、`create_time` / `update_time`（MyBatis-Plus 自动填充）、面向用户数据逻辑删除（`deleted` + `@TableLogic`）、**不加数据库级外键**但关联列全部建索引。

迁移脚本：`src/main/resources/db/V001__init.sql`（6 张表 + 索引）、`V002__word_progress.sql`。**每个脚本可重复执行**（`CREATE TABLE IF NOT EXISTS`）以便课设演示。

### 3. 认证模块（5 接口）

| 接口 | 要点 |
|---|---|
| `POST /api/client/auth/register` | 用户名 4–20 位字母/数字/下划线，密码 ≥6 位含字母+数字；重复 → 1001；格式错 → 1005；BCrypt 入库；默认 role=USER、status=1、nickname 缺省取 username |
| `POST /api/client/auth/login` | 失败 → 1002；禁用 → 1003；成功签发 JWT（userId、role、7 天）；失败记 WARN 日志**不含密码**；管理员走同一接口由 role 区分 |
| `POST /api/client/auth/wx-login` | `code2Session` 换 openid；openid 存在则登录；不存在自动建用户（昵称「微信用户+随机后缀」、头像 avatar_1）并返回 `isNewUser:true`；微信侧异常 → 1004 |
| `GET /api/client/auth/profile` | 返回 userId/username/nickname/avatar/role/createTime/`totalCheckInDays` |
| `PUT /api/client/auth/profile` | 仅 nickname（≤20 字）与 avatar（**仅 avatar_1~avatar_6**）；至少一项；违规 → 1005；返回完整 profile |

### 4. 词库模块（4 接口）

| 接口 | 要点 |
|---|---|
| `GET /api/client/books?category=` | category 可选（CET4/CET6/KAOYAN）；元素含 `selected`（该用户是否已加入）、`learnedCount` |
| `GET /api/client/books/{bookId}?page=&size=` | 含单词分页；不存在 → 2001 |
| `POST /api/client/books/{bookId}/select` | 不存在 → 2001；重复 → 2003 |
| `DELETE /api/client/books/{bookId}/select` | 不存在 → 2001；未选择 → 2002；**历史学习记录保留** |

### 5. 学习模块（4 接口）— 核心链路

| 接口 | 要点 |
|---|---|
| `GET /api/client/study/plan` | 每本已选词书每日 20 新词（常量 `DAILY_NEW_WORDS=20`），按 `word.id` 升序取未学词；**复习词（isReview=true）排在新词前**；`learned=true` 的词仍返回（前端跳过）；未选词书 → `total=0, words=[]` |
| `POST /api/client/study/record` | `{wordId, bookId, known}`；`known=false` 时**立即收录错题本**；响应 `{wordId, learned, addedToMistakes}` |
| `POST /api/client/study/checkin` | 前置：当日计划词全部 learned，否则 3003；未选词书/无计划 → 3002；已打卡 → 3001（幂等）；响应 `checkInDate/streakDays/todayLearned/todayMistakes` |
| `GET /api/client/study/streak` | `streakDays/totalLearned/todayReviewCount/lastCheckInDate` |

**连续天数规则**：上次打卡日期 = 昨天 → `streak+1`；否则重置为 `1`。

### 6. 复习模块（2 接口）

| 接口 | 要点 |
|---|---|
| `GET /api/client/review/today` | `next_review_date <= CURDATE()` 且 `learned=1` 且未掌握；响应含 `stage`、`wrongCount`；不建表存储（由 `word_progress` 计算） |
| `POST /api/client/review/record` | `{wordId, correct}`；correct=true → stage+1（封顶 4）；correct=false → stage=1、`wrong_count`+1、确保收录错题本；响应 `{wordId, stage, nextReviewDate}` |

**间隔序列常量**：`1 → 3 → 7 → 15` 天，写死为常量。

### 7. 错题模块（3 接口）

| 接口 | 要点 |
|---|---|
| `GET /api/client/mistakes?mastered=&page=&size=` | `mastered` 缺省 0；元素含 word/phonetic/translation/source/wrongCount/mastered/createTime |
| `POST /api/client/mistakes/{id}/mastered` | 不存在 → 4001；已掌握 → 4002 |
| `DELETE /api/client/mistakes/{id}` | 逻辑删除；不存在 → 4001 |

**收录规则**：同词重复错 → `wrong_count+1`，**不重复建记录**。

### 8. AI 模块（3 接口，全部经本服务中转并可降级）

| 接口 | 超时 | 降级行为 |
|---|---|---|
| `POST /api/client/ai/example` | 15s | 失败 HTTP 仍 200、`code:"0"`、`degraded:true`、填 `word.example`、`mnemonic` 空 |
| `POST /api/client/ai/explain` | 30s | **无兜底内容**，返回 5001/5002 |
| `POST /api/client/ai/report` | 60s | 学习天数 < 3 → 5003 **且不调用 AI**；失败 → 5001/5002 |

- 前端只传 `wordId` / `mistakeId`，**单词文本由后端从库中取出**传给 AI。
- 所有降级记 WARN 日志（功能名、耗时、降级结果），并计入内存计数器。
- **必须有显式超时**（RestTemplate/WebClient timeout），不能让请求无限挂起。

### 9. 统计模块（2 接口）

| 接口 | 要点 |
|---|---|
| `GET /api/client/stats/heatmap?weeks=12` | 按 `check_in_date` 聚合近 12 周；`level` 后端分档 0=未打卡 / 1=1–10 词 / 2=11–20 词 / 3=21+ 词；**SQL 聚合，不加载原始记录** |
| `GET /api/client/stats/mastery?days=30` | 按天聚合 `learnedCount/reviewCount/mistakeCount/masteryRate`；`masteryRate` = 已掌握错题 / 总错题 × 100 取整 |

### 10. 管理模块（7 契约接口 + 3 补齐接口 = 10）

| 接口 | 要点 |
|---|---|
| `POST /api/admin/books/import` | multipart；字段 `file`(.jsonl) / `name` / `category`；逐行校验跳过计数 → `{bookId, success, skipped}`；整体非法 → 2004；**前端会先预览前 20 行**故需保证解析确定性 |
| `GET /api/admin/words?bookId=&keyword=&page=&size=` | 单词分页，元素含 `wordId` |
| `POST /api/admin/words` | 成功后 `wordCount+1`；词书不存在 → 2001 |
| `PUT /api/admin/words/{wordId}` | 字段均可选 |
| `DELETE /api/admin/words/{wordId}` | 逻辑删除，`wordCount−1` |
| `GET /api/admin/users?keyword=&page=&size=` | 元素含 `role`/`status`/`totalCheckInDays`/`createTime` |
| `PUT /api/admin/users/{userId}/status` | `{status:0|1}`；**不允许禁用 ADMIN → 1005**；操作记 INFO 日志（操作者 id、动作、目标用户） |
| `GET /api/admin/books` **[补齐]** | 词书分页列表，供词库管理页表格 |
| `DELETE /api/admin/books/{bookId}` **[补齐]** | 逻辑删除词书（连带单词逻辑删除） |
| `GET /api/admin/monitor/ai-stats` **[补齐]** | AI 调用次数/成功率/降级次数 + 降级事件列表（内存计数器 + 日志聚合） |

### 11. 种子词库导入

- 三本词书：四级核心词汇(7508) / 六级核心词汇(5651) / 考研核心词汇(9602)，`category` 分别为 CET4/CET6/KAOYAN。
- 映射：`word→word`、`uk→phonetic`、`translations` 拼 `"{type}. {translation}"`（多项 `；` 连接）→`translation`、`sentences[0].sentence→example`。
- 导入后 `word_book.word_count` 必须与实际行数**严格一致**。
- **复用管理端导入的同一套解析逻辑**（禁止两套映射代码）。

---

## 验收标准

### 功能正确性

- [x] 7 张表迁移脚本在空库上执行成功，重复执行不报错；索引与唯一约束与 S3 §7 一致。（G2 复核：55/55 列、14/14 索引逐字核对；`verify-schema-entities.py`）
- [x] 三本词书导入后 `word_count` = 7508 / 5651 / 9602，可用 SQL 核对。（G3 + 阶段 10 冒烟 B1 复核）
- [x] 全部对外接口（33 个 = 契约 30 + 补齐管理端 3）逐条自测通过，留存可执行脚本与输出。（`backend/scripts/smoke-test.sh` 一键跑 143 用例；分脚本 g4-smoke/g4-crossday/g5-smoke/stage9-stats-smoke/stage10-admin-smoke）
- [x] 一条完整链路走通：注册 → 选词书 → 学 20 词（含「不认识」）→ 打卡 → 错题本有该词 → 复习页见到它 → 答错后 stage 退回 1 且 `next_review_date` = 明天 → 统计页有数据。（`g4-smoke.py` + `stage9-stats-smoke.py`）
- [x] 打卡幂等：连续两次打卡，第二次返回 3001 且 `study_record` 无重复行、`streak_days` 不变。（`g4-smoke.py`）
- [x] 连续天数：构造「昨天打卡 + 今天打卡」→ streak+1；构造「中断两天后打卡」→ streak 重置为 1。（`g4-crossday.py`）
- [x] 间隔复习：连续答对 4 次 → stage 依次 2/3/4/4（封顶）；答错一次 → stage 立即为 1。（`g4-crossday.py` + `ReviewScheduleTest`）
- [x] 错题去重：同词两次「不认识」→ 一条 `mistake_entry`，`wrong_count=2`。（`g4-smoke.py` + `MistakeServiceImplTest`）

### 契约一致性

- [x] 逐字段对照 `docs/接口详细设计.md`，路径/字段名/类型/错误码零偏差。（契约已冻结 V1.1，阶段 10 严格照 §8 实现；管理端 46 用例逐字段核对）
- [x] **先改文档**：补齐的 3 个管理端接口已写入《接口详细设计》§8 并在 §10 变更记录登记。（G6，提交 `29e8e5f`，先于阶段 10 编码）
- [x] `word_progress` 表的偏离已记入 design.md，并已同步 S3（§7.1 表数 6→7 + 表清单，S3 V1.3）。
- [x] 业务失败 HTTP 状态为 200；401/403/404/405/415 仅按契约语义使用（`GlobalExceptionHandlerIntegrationTest` + `JwtInterceptorIntegrationTest` 锁死）。

### 降级与安全

- [x] **ai-service 未启动**时：`/api/client/ai/example` 返回 `code:"0"` + `degraded:true` + 内置例句；`explain`/`report` 返回 5001/5002。（G5：`g5-smoke.py` 刻意不启动 ai-service；另用 `silent-ai-server.py` 压短超时验证真实超时→5001）
- [x] 学习/打卡/复习链路在 ai-service 宕机时完全不受影响。（G5 D1/D2/D3 用例）
- [x] 降级事件可通过 `GET /api/admin/monitor/ai-stats` 观测到计数增加。（阶段 10 冒烟 F4：真实降级后 `degradedCount` +1）
- [x] 日志中 grep 不到密码、token、AI key。（G5 实测 grep 0 命中；另修生产默认 `com.vocab.mapper` DEBUG→INFO，杜绝 SQL 日志打印 BCrypt 哈希）
- [x] 禁用用户后其 token 立即失效（401 + 1003 文案）；禁用 ADMIN 返回 1005。（阶段 10 集成测试 + 冒烟 E6/E8）
- [x] `application.yml` 无明文密码，全部环境变量占位；本地配置被 `.gitignore` 排除。（`git ls-files application-local.yml` = 0 命中）

### 可交付性

- [x] `mvn -o clean package` 构建通过（含测试）——**326 tests, 0 failures, 0 errors**。
- [x] 含 `README.md`：环境要求、建库步骤、脚本执行顺序、环境变量清单、种子导入、启动命令、自测方法（已按最终交付状态重写）。
- [x] 有可运行的接口自测脚本：`backend/scripts/smoke-test.sh`（一键）+ 5 个分脚本，根目录 `scripts/smoke-test.sh` 为转发入口。

---

## 非目标（本子任务不做）

- 不做 AI 生成内容的真实实现（那是 `ai-service` 子任务）；本任务只做**调用方 + 降级**。
- 不做任何前端代码。
- 不做完整监控平台；监控只需内存计数器 + 日志聚合满足演示。
- 不做单元测试覆盖率指标强制；但核心算法（streak、间隔复习、打卡幂等）**必须有测试**。
