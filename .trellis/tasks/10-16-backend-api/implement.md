# backend 执行计划

> 有序清单 + 验证命令 + 评审闸口 + 回滚点。需求见 `prd.md`，技术设计见 `design.md`。

## 0. 分支与提交策略

- 单开发者项目，**直接在 `main` 上按阶段提交**（每阶段一个逻辑提交，git 历史本身是 S4 交付物的一部分）。
- 提交信息格式：`feat(backend): ...` / `test(backend): ...` / `docs(contract): ...` / `chore(db): ...`
- 每个阶段结束后运行该阶段验证命令，**验证通过才进入下一阶段**。

## 1. 前置准备（阶段闸口：数据库可连、词库文件落盘）

1. [ ] 建库：`CREATE DATABASE IF NOT EXISTS ai_vocab DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`（通过 DBX MCP `aivocab-root` 连接执行）
2. [ ] 词库落盘到 `data/vocab-source/`：`四级.jsonl` / `六级.jsonl` / `考研.jsonl`
   - 已实测可行路径：`git clone --depth 1 --filter=blob:none --sparse` + `git sparse-checkout set --no-cone`（逐个文件加，一次加多个会失败）
   - 三份文件总计约 24MB，**加入 `.gitignore`**（不入库）
   - 同时落盘 `LICENSE`（BSD 3-Clause，用户手册要用）
3. [ ] 校验行数：`wc -l` 必须为 7508 / 5651 / 9602

**验证**：`SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='ai_vocab'` 有结果；三个 jsonl 行数正确。

## 2. 工程骨架（阶段闸口：`mvn clean package` 通过、空应用能启动）

4. [x] `backend/pom.xml`：Spring Boot 3.5.x parent、Java 17、依赖
   - `spring-boot-starter-web`、`spring-boot-starter-validation`
   - `mybatis-plus-spring-boot3-starter` 3.5.9
   - `mysql-connector-j`
   - `spring-security-crypto`（只取 BCrypt，**不引 spring-boot-starter-security**）
   - `jjwt-api`/`jjwt-impl`/`jjwt-jackson` 0.12.6
   - `spring-boot-starter-test`、`h2`（test scope，供单测）
   - ➕ `mybatis-plus-jsqlparser` 3.5.9（**计划外新增**：3.5.9 起
     `PaginationInnerInterceptor` 被拆到独立模块，不引则编译失败、分页静默失效）
5. [x] `application.yml`（占位符） + `application-local.yml`（gitignore，真实凭据） + `.gitignore` 更新
6. [x] `common/`：`Result`、`ErrorCode`（枚举，含全部已分配码值）、`BizException`、`PageResult`、`Constants`（`DAILY_NEW_WORDS=20`、`REVIEW_INTERVALS=[1,3,7,15]`）、`GlobalExceptionHandler`
7. [x] `config/`：`MybatisPlusConfig`（分页插件 + `MetaObjectHandler`）、`JwtUtil`、`JwtInterceptor`、`WebMvcConfig`（拦截器注册 + CORS）、`RestTemplateConfig`
   - ➕ `UserContext`（ThreadLocal 承载当前用户，拦截器写入 / `afterCompletion` 清理）
   - ➕ `UserStatusValidator` + ObjectProvider 接入（解耦「禁用即时生效」的查库校验）
8. [x] `VocabApplication.java`

**验证**：`mvn -q clean package` 成功；`mvn spring-boot:run` 能启动（数据库连上，无表也可启动）。

**实施结果（耗时/证据）**：
- `mvn clean package` ✅ 产出 `target/vocab-backend-1.0.0.jar`（30.6 MB）；`mvn -o` 离线构建亦通过。
- `mvn spring-boot:run -Dspring-boot.run.profiles=local` ✅ 2.35s 启动（Tomcat 8080），无 WARN。
- 单测 ✅ 37 个全绿：`ResultAndErrorCodeTest`(11) / `JwtUtilTest`(7) / `JwtInterceptorIntegrationTest`(11) /
  `GlobalExceptionHandlerIntegrationTest`(8)。
- 实测响应：无 token → `HTTP 401` + `{"code":"1006",...}`；CORS 预检 → 200；
  不存在路径 → `HTTP 404` + `9002`（**未**被兜底吞成 200，见下方缺口）。

**本阶段契约增补（\u2192 需在 G6 前回写 docs/接口详细设计.md）**：
- 补 `1006 TOKEN_INVALID`（契约只给「账号禁用」分配 1003，token 无效/过期无码值）。
- 补 `1007 FORBIDDEN`（契约 §0 规定 403 语义但未分配码值，导致 403 响应体无法构成 `Result`）。
- 补 `9002/9003/9004`（404/405/415：契约「业务失败 HTTP 200」不含路由层错误，
  若一并吞成 200 会把「路径写错」伪装成「系统繁忙」，联调无法定位）。
- CORS 白名单改为配置项 `cors.allowed-origins`（原计划硬编码 localhost:5173）。

## 3. 数据库迁移脚本（阶段闸口：脚本可重复执行、索引齐全）

9. [ ] `db/V001__init.sql`：`user` / `word_book` / `word` / `user_word_book` / `study_record` / `mistake_entry`
   - 每表 `CREATE TABLE IF NOT EXISTS`
   - 公共列：`id` BIGINT AI PK、`create_time`/`update_time` DATETIME NOT NULL
   - 索引严格按 S3 §7.1 表清单 + design.md §4.2 的加固唯一索引
   - 表名/列名加反引号
10. [ ] `db/V002__word_progress.sql`：`word_progress` 表（DDL 见 design.md §3.2）
11. [ ] 执行两个脚本；**再执行一次**验证幂等

**验证**（DBX MCP）：
- `SHOW TABLES` 返回 7 张表
- `SHOW INDEX FROM study_record` 含加固唯一索引
- `SELECT COUNT(*) FROM (SELECT 1) x` 无异常；脚本二次执行无报错

## 4. 实体与 Mapper（阶段闸口：MyBatis-Plus 能读写 7 张表）

12. [ ] 7 个 `entity/` 类：`@TableName`、`@TableId(IdType.AUTO)`、`@TableLogic deleted`、`@TableField(fill=...)` 时间字段
13. [ ] 7 个 `mapper/` 接口，继承 `BaseMapper<T>`
14. [ ] `resources/mapper/StatsMapper.xml`（热力图/掌握度聚合）；逻辑删除条件**手工补 `deleted = 0`**（规范红线）

**验证**：写一个 `@SpringBootTest` 或临时 `CommandLineRunner` 对每张表做一次 `selectCount(null)`，确认无 SQL 错误。

## 5. 认证模块（阶段闸口：5 个 auth 接口自测通过）

15. [ ] `AuthService`：注册（格式校验 + 唯一性 + BCrypt）、登录（失败 1002 → WARN 日志不含密码；禁用 1003）、微信登录（`code2Session`，真实 HTTP 调用微信 API；openid 不存在则建用户返回 `isNewUser:true`）、`profile` 查询（含 `totalCheckInDays`）、`profile` 更新（nickname ≤20、avatar 白名单 `avatar_1..6`）
16. [ ] `AuthController` + 4 个 request DTO + 3 个 response DTO
17. [ ] JWT 签发（userId/role/7 天）与 `JwtInterceptor` 全链路生效

**验证**：
```bash
# 注册
curl -s -X POST localhost:8080/api/client/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"zhangsan","password":"abc123","nickname":"张三"}'
# 重复注册 → 1001
# 登录 → 拿 token
# 无 token 访问 profile → HTTP 401
# 错误密码 → 1002
# 手动 UPDATE user SET status=0 → 同一 token 再访问 → 401 + 1003
# avatar 传 "avatar_9" → 1005
```
- 微信登录 **无 AppID 时**：用单测 mock 掉微信 HTTP 调用，验证 openid 新建/复用两条分支。
- [ ] `AuthServiceTest`：注册重复用户名、密码强度、nickname 长度、avatar 白名单

## 6. 词库模块 + 种子导入（阶段闸口：三本词书入库且计数正确）

18. [ ] `WordImportService.parseLine`（design.md §7.1，**唯一映射实现**）
19. [ ] `WordBookService`：`listBooks(category, userId)`（含 `selected` / `learnedCount`）、`bookDetail(bookId, page)`、`select`、`deselect`
20. [ ] `BookController` + response DTO
21. [ ] `VocabSeedRunner`（profile/参数控制的自举导入，从 `data/vocab-source/` 读，`saveBatch` 分批 1000）
22. [ ] 执行种子导入

**验证**（DBX MCP）：
```sql
SELECT b.name, b.category, b.word_count, (SELECT COUNT(*) FROM word w WHERE w.book_id=b.id AND w.deleted=0) actual
FROM word_book b;
```
- 三行 `word_count == actual`，分别为 7508 / 5651 / 9602
- `SELECT word, phonetic, translation, example FROM word WHERE book_id=? LIMIT 5` 抽查映射正确（多义项拼接含 `；`）
- `GET /api/client/books` / `?category=CET4` / `/{id}?page=1&size=20` 返回结构符合契约
- select 两次 → 第二次 2003；deselect 未选的 → 2002

## 7. 学习 + 复习 + 错题（阶段闸口：核心闭环端到端跑通）

23. [ ] `StudyService.plan(userId)`：按 design.md §4.1 生成（复习词在前、每词书 20 新词、`learned` 标记）
24. [ ] `StudyService.record(userId, req)`：写 `word_progress`（upsert）、`known=false` 立即收录错题、首次学习设置 `stage=1, next_review_date=+1天`
25. [ ] `StudyService.checkIn(userId)`：design.md §4.2（3001 幂等 / 3002 / 3003 / streak 计算）
26. [ ] `StudyService.streak(userId)`
27. [ ] `ReviewService.today(userId)`：`next_review_date <= CURDATE() AND learned=1` 且未掌握
28. [ ] `ReviewService.record(userId, req)`：design.md §4.3（答对 stage+1 封顶 4；答错 stage=1、`wrong_count`+1、确保收录错题）
29. [ ] `MistakeService`：`list`（分页 + `mastered` 筛选）、`markMastered`（4001/4002）、`remove`（逻辑删除，4001）
30. [ ] 三个 Controller + DTO

**验证（含算法单测）**：
- [ ] `StudyServiceTest`：streak 连续 +1；中断重置为 1；同日重复打卡返回 3001 且无重复行
- [ ] `ReviewServiceTest`：连对 4 次 stage 依次 2/3/4/4；答错后 stage=1 且 `next_review_date`=明天
- [ ] `MistakeServiceTest`：同词两次错误 → 一条记录 `wrong_count=2`；标记已掌握后再次答错 → `mastered` 回到 0
- [ ] 手工全链路 curl 脚本：注册 → select 词书 → plan（20 词）→ record ×20（其中一个 known=false）→ checkin → mistakes 有 1 条 → 手工把 `next_review_date` 改成昨天 → review/today 见到该词 → review/record correct=false → 查 `word_progress` stage=1、`mistake_entry.wrong_count=2`

## 8. AI 中转与降级（阶段闸口：ai-service 未启动时降级路径全绿）

31. [ ] `AiService`：`example` / `explain` / `report`，各自超时 15/30/60s（`RestTemplateBuilder` 每次构造）
32. [ ] `AiMetrics`：内存计数器 + 环形降级事件队列（容量 100）
33. [ ] `AiController` + DTO
34. [ ] **`example` 降级不抛异常**（HTTP 200 + `code:"0"` + `degraded:true` + `word.example`）——代码中加显式注释
35. [ ] `report` 前置：学习天数 < 3 → 5003 且**不发起 HTTP**
36. [ ] `explain` 降级 → 抛 5001/5002

**验证（关键闸口）**：
- [ ] 确保 ai-service **未启动**，跑：
  - `POST /api/client/ai/example` → `code:"0"`、`degraded:true`、`example` 非空（来自词库）
  - `POST /api/client/ai/explain` → `code:"5002"`
  - `POST /api/client/ai/report`（学习天数 <3）→ `code:"5003"`
  - `POST /api/client/ai/report`（学习天数 ≥3）→ `code:"5002"`
- [ ] **同时**验证学习/打卡/复习完全不受影响（重跑阶段 7 的链路）
- [ ] `AiServiceTest`：mock RestTemplate 抛 `ResourceAccessException`（超时）与 500，断言降级行为

## 9. 统计模块（阶段闸口：SQL 聚合正确，无内存加载）

37. [ ] `StatsMapper.xml`：热力图按 `check_in_date` 聚合近 12 周；掌握度按天聚合近 30 天
38. [ ] `StatsService`：补零日期序列（84 天连续）、`level` 分档（`0/1–10/11–20/21+`）、`masteryRate` 取整
39. [ ] `StatsController` + DTO

**验证**：
- [ ] 造多天数据后，`GET /stats/heatmap` 返回 84 个点、日期连续、`level` 与 `wordCount` 匹配分档
- [ ] `GET /stats/mastery?days=30` 返回 30 个点，`masteryRate` 与手工 SQL 计算一致
- [ ] 确认 `StatsMapper.xml` 中**没有**加载原始记录到 Java 的写法

## 10. 管理模块（阶段闸口：先改契约文档，再改代码）

40. [ ] **先回写 `docs/接口详细设计.md`**：
    - §8 新增 8.8 `GET /api/admin/books`、8.9 `DELETE /api/admin/books/{bookId}`、8.10 `GET /api/admin/monitor/ai-stats`（含字段表与示例）
    - §10 变更记录新增 V1.1 行（说明补齐的管理端接口 + `word_progress` 表 + `study_record` 加固唯一索引）
    - 单独提交：`docs(contract): 补齐管理端接口并记录 word_progress 偏离`
41. [ ] `WordBookService.import(multipart)`：解析计数 → `saveBatch` → 校正 `word_count` → `{bookId, success, skipped}`；整体非法 → 2004
42. [ ] `AdminWordController`：分页查询（bookId/keyword）、新增（`wordCount+1`）、修改、删除（逻辑删除，`wordCount−1`）
43. [ ] `AdminUserController`：分页 + keyword 搜索、禁用/启用（**ADMIN 不可禁用 → 1005**）、操作 INFO 日志（操作者 id、动作、目标用户）
44. [ ] `AdminBookController`：`GET /api/admin/books`、`DELETE /api/admin/books/{bookId}`
45. [ ] `AdminMonitorController`：`GET /api/admin/monitor/ai-stats`
46. [ ] 全部 `/api/admin/**` 校验 ADMIN（普通用户 → 403）

**验证**：
- [ ] 普通用户 token 访问任一 `/api/admin/**` → HTTP 403
- [ ] 导入一份**构造的含坏行**JSONL（如 3 行，1 行缺 `word`）→ 响应 `success:2, skipped:1`
- [ ] 单词新增/删除后 `word_count` 正确 ±1
- [ ] 禁用某用户 → 该用户 token 立即 401；禁用 ADMIN → 1005
- [ ] `ai-stats` 能看到阶段 8 产生的降级计数

## 11. 收尾（阶段闸口：可交付）

47. [ ] `backend/README.md`：环境要求、建库、脚本执行顺序、环境变量清单、种子导入、启动、自测方法
48. [ ] `scripts/smoke-test.sh`：一键跑通全部接口断言（含降级路径）
49. [ ] `mvn -q clean package` 全绿（含测试）
50. [ ] 自查 `prd.md` 的**每一条**验收标准，逐条打勾

---

## 评审闸口（必须停下来人工确认）

| # | 位置 | 闸口内容 |
|---|---|---|
| G1 | 阶段 1 后 | 建库与词库落盘完成，行数正确 |
| G2 | 阶段 3 后 | 7 张表结构与 S3 §7 + design.md 一致 |
| G3 | 阶段 6 后 | 三本词书计数 7508/5651/9602 严格一致 |
| G4 | 阶段 7 后 | 核心闭环端到端跑通 + 算法单测通过 |
| G5 | 阶段 8 后 | **降级路径在 ai-service 未启动时全绿**（一等约束） |
| G6 | 阶段 10 前 | 契约文档已回写（先文档后代码，不可颠倒） |
| G7 | 阶段 11 后 | 全部验收标准勾选，交付物齐全 |

## 验证命令速查

```bash
# 构建
cd backend && mvn -q clean package

# 启动（本地配置）
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 种子导入
mvn spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments=--vocab.seed=true

# 自测
bash backend/scripts/smoke-test.sh

# 数据库核对（DBX MCP）
python .tmp/dbx.py dbx_execute_query '{"connection_name":"aivocab-root","database":"ai_vocab","sql":"..."}'
```

## 回滚点

| 回滚点 | 触发条件 | 动作 |
|---|---|---|
| R1 | 骨架阶段构建失败且无法快速修复 | `git reset --hard` 到阶段 1 后的提交 |
| R2 | `word_progress` 设计被证明有缺陷 | 不修改 `V002`，新增 `V003__fix_word_progress.sql` |
| R3 | 词库导入数据错乱 | `DELETE FROM word WHERE book_id=?` 后重导（`data/` 源文件保留） |
| R4 | 全库崩坏 | `DROP DATABASE ai_vocab` 后从 `V001`/`V002` 重建（**绝不触碰 `anime_tracker`**） |

## 已知需后续同步的文档

- [ ] S3 §7 表数 6 → 7（`word_progress`）
- [ ] S3 §7.5 `study_record` 补加固唯一索引说明
- [ ] S3 §8.2 待确定问题登记 AI 供应商为 DeepSeek
- [ ] `docs/接口详细设计.md` §8/§10（阶段 40 已完成）
- [ ] **`docs/接口详细设计.md` §0 错误码表**：补 `1006/1007/9002/9003/9004`
      及「HTTP 4xx 保留给路由层错误」的边界说明
      → 详见 `research/01-contract-gaps-stage2.md`
