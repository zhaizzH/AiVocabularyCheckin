# backend 执行计划

> 有序清单 + 验证命令 + 评审闸口 + 回滚点。需求见 `prd.md`，技术设计见 `design.md`。

## 0. 分支与提交策略

- 单开发者项目，**直接在 `main` 上按阶段提交**（每阶段一个逻辑提交，git 历史本身是 S4 交付物的一部分）。
- 提交信息格式：`feat(backend): ...` / `test(backend): ...` / `docs(contract): ...` / `chore(db): ...`
- 每个阶段结束后运行该阶段验证命令，**验证通过才进入下一阶段**。

## 1. 前置准备（阶段闸口：数据库可连、词库文件落盘）

1. [x] 建库：`CREATE DATABASE IF NOT EXISTS ai_vocab DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`（通过 DBX MCP `aivocab-root` 连接执行）
2. [x] 词库落盘到 `data/vocab-source/`：`CET4.jsonl`(7,403,986 B) / `CET6.jsonl`(4,340,935 B) / `KAOYAN.jsonl`(8,627,487 B)
   - 已实测可行路径：`git clone --depth 1 --filter=blob:none --sparse` + `git sparse-checkout set --no-cone`（逐个文件加，一次加多个会失败）
   - 三份文件总计约 24MB，**加入 `.gitignore`**（不入库）
   - 同时落盘 `LICENSE`（BSD 3-Clause，1,528 B；用户手册要用）
3. [x] 校验行数：`wc -l` = **7508 / 5651 / 9602**（0 坏行）

**验证**：`SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='ai_vocab'` ✅ 有结果；三个 jsonl 行数 ✅ 正确。

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

9. [x] `db/V001__init.sql`：`user` / `word_book` / `word` / `user_word_book` / `study_record` / `mistake_entry`
   - 每表 `CREATE TABLE IF NOT EXISTS`；含 `CREATE DATABASE IF NOT EXISTS ai_vocab`（空库可自举）
   - 公共列：`id` BIGINT AI PK、`create_time`/`update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP（+ON UPDATE）
   - 索引严格按 S3 §7.1 表清单 + design.md §4.2 的加固唯一索引
   - 表名/列名加反引号；显式 `ENGINE=InnoDB COLLATE=utf8mb4_0900_ai_ci`
10. [x] `db/V002__word_progress.sql`：`word_progress` 表（DDL 见 design.md §3.2）
11. [x] 执行两个脚本；**再执行一次**验证幂等；另做 **DROP DATABASE 后空库自举重建**

**验证结果（实测，pymysql 直连；未用 mysql CLI——本机未安装）**：
- `SHOW TABLES` → **7 张表**，列名与顺序 100% 匹配 S3 表 7.1~7.6 + design §3.2（脚本化比对，7/7 通过）。
- 索引实测（**G2 复核后为 S3 §7.1 原样命名**）：
  `user`: `uk_username` / `uk_openid`；`word_book`: `idx_category`；`word`: `idx_book_id`；
  `user_word_book`: `idx_user_id` / `idx_book_id` + 加固唯一 `uk_user_word_book_user_book(user_id,book_id)`；
  `study_record`: `idx_user_date(user_id,study_date)` / `idx_user_book(user_id,book_id)`
  + 加固唯一 `uk_study_record_user_word_date_type(user_id,word_id,study_date,study_type)`；
  `mistake_entry`: `idx_user_id`；`word_progress`: 唯一 `uk_word_progress_user_word(user_id,word_id)`
  / `idx_word_progress_user_review(user_id,next_review_date)` / `idx_word_progress_user_book_learned(user_id,book_id,learned)`。
  共 14 个业务索引，全部 `NON_UNIQUE` 值与预期一致（唯一索引 `NON_UNIQUE=0`）。
- 时间列：7 表 × 2 列均为裸 `DATETIME NOT NULL`，无 `DEFAULT` / `ON UPDATE`（设计决策见下第 2 条）。
- 引擎/字符集：7 表均 InnoDB / utf8mb4_0900_ai_ci；**数据库级外键 0 个**（符合规范）。
- **约束行为实测**（不只验 DDL 声明）：
  - 重复 `username` → 报 `Duplicate entry 'dup' for key 'user.uk_username'` ✅
  - 两个 `username=NULL` 行均可插入 ✅（微信用户场景，MySQL 唯一索引允许多 NULL）
  - 同一用户重复选同一词书 → `Duplicate entry '5-1' for key 'user_word_book.uk_user_word_book_user_book'` ✅
  - 同用户同词同日同类型 `study_record` 重复行 → 被拦截 ✅（并发重复打卡防线）
  - `word_progress` `INSERT ... ON DUPLICATE KEY UPDATE` → 始终 1 行、`stage` 正确更新为 2 ✅（学习上报幂等）
- **幂等**：两脚本连续执行两遍，退出码均为 0。
- **空库自举**：`DROP DATABASE ai_vocab` 后仅执行 `V001`+`V002` 即重建出 7 张表（库字符集 utf8mb4 / utf8mb4_0900_ai_ci），证明 R4 回滚路径可用；`anime_tracker` 全程未触碰（每步验证其仍存在）。
- 两个脚本已确认打入产物 jar 的 `BOOT-INF/classes/db/`。
- 构建回归：`mvn -o clean package` ✅ **37 个测试全绿**。

**阶段 3 实施记录（设计决策补充）**：
- 列宽实测校准：导入源最大 `word=18` / `uk=58` / `translation=126` / `example=200`，
  均落在 S3 定义宽度内（100/100/255/500），**无需调整**。
  注：`sql_mode` 含 `STRICT_TRANS_TABLES`，若超宽会直接报错而非静默截断——故先实测再定宽。
- `mistake_entry` **刻意不加** `(user_id, word_id)` 唯一索引：逻辑删除的历史行仍在表中，
  「移除后重新收录」会与历史行冲突。去重由应用层保证（design.md §4.4）。此为有意偏离
  「唯一索引防重」的直觉做法，理由已写入脚本注释。
- S3 §5.4 原文「复习列表为计算生成，不设独立存储表」，而本设计 `word_progress` 存的是
  **每词进度状态**（stage / next_review_date）而**非列表快照**；待复习列表仍是实时计算。

**G2 复核调整（用户评审后执行，设计决策反转）**：

1. **索引名回退为 S3 §7.1 原样**（此前曾按规范补表名前缀，现反转）：
   `uk_username` / `uk_openid` / `idx_category` / `idx_book_id` / `idx_user_id` /
   `idx_book_id` / `idx_user_date` / `idx_user_book` / `idx_user_id`。
   理由：S3 是课设正式交付物，**逐字一致性优先于内部命名规范**（评审会对照文档核查脚本）。
   加固新增的唯一索引仍带表名前缀（`uk_user_word_book_user_book` / `uk_study_record_user_word_date_type`），
   因 S3 未定义、需避免歧义。详见 design.md §3.4。
2. **移除 DB 层时间默认值**：`create_time` / `update_time` 改为裸 `DATETIME NOT NULL`，
   不再有 `DEFAULT CURRENT_TIMESTAMP` / `ON UPDATE CURRENT_TIMESTAMP`，
   时间戳全部由 MyBatis-Plus `MetaObjectHandler` 单套填充（避免 ORM 与 DB 双写分叉）。
   传导约束：阶段 4 实体必须标 `@TableField(fill=INSERT / INSERT_UPDATE)`，否则插入报
   `1364 Field 'create_time' doesn't have a default value`（已实测）。详见 design.md §3.5。
3. **其余逐字对齐 docs/S3 §7**：已完成列级严格比对（见下）。
4. `word_progress` 按用户决定**保持现状**（字段集与索引结构不变，仅同步去除时间默认值）。

**G2 复核验证结果（实测，pymysql 直连）**：
- 空库自举：`DROP DATABASE ai_vocab` 后仅执行 V001+V002 → **7 张表**（全部 InnoDB / utf8mb4_0900_ai_ci）。
- **列定义严格比对 S3**：6 张表逐字段（列名 + 类型 + 长度 + 可空性 + 顺序）
  **全部 100% 匹配**（55 列：user 11 / word_book 8 / word 11 / user_word_book 5 /
  study_record 12 / mistake_entry 9）；`word_progress` 11 列与 design.md §3.2 一致。
- **索引逐字核对**：14/14 通过（名称 + 列组合 + 唯一性，脚本断言 ALL PASS，无多余索引）。
- **时间列**：14 个时间列（7 表 × 2）`COLUMN_DEFAULT` 全为 NULL、`EXTRA` 无 `DEFAULT`/`ON UPDATE`；
  反证：不带 `create_time` 的裸 INSERT 被拒（`1364`），带值则成功。
- **约束行为实测**：重复 `username` → `Duplicate entry 'dup' for key 'user.uk_username'` ✅；
  多个 NULL `openid` 均可插入 ✅；重复选书 → `uk_user_word_book_user_book` 拦截 ✅；
  同日同词同类型 `study_record` → `uk_study_record_user_word_date_type` 拦截 ✅；
  `word_progress` upsert 始终 1 行、stage 更新为 2 ✅。
- **幂等**：V001+V002 连跑两遍均 exit 0，仍为 7 表。
- **红线**：`anime_tracker` 全程 20 表、最后建表时间未变（`UNCHANGED: True`）。
- 构建回归：`mvn -o clean package` ✅ 37 测试全绿；两脚本已确认打入 jar 且与磁盘 md5 一致。

## 4. 实体与 Mapper（阶段闸口：MyBatis-Plus 能读写 7 张表）—— **已完成**

12. [x] 7 个 `entity/` 类：`@TableName`、`@TableId(IdType.AUTO)`、`@TableLogic deleted`、`@TableField(fill=...)` 时间字段
13. [x] 7 个 `mapper/` 接口，继承 `BaseMapper<T>`
14. [x] `resources/mapper/StatsMapper.xml`（热力图/掌握度聚合）；逻辑删除条件**手工补 `deleted = 0`**（规范红线）

**验证**：`@SpringBootTest`（H2）对 7 张表做 `selectCount(null)` + 全字段往返，无 SQL 错误。

**阶段 4 实施记录（已完成，54 测试全绿）**：

产出文件：
- `entity/`：`User` / `WordBook` / `Word` / `UserWordBook` / `StudyRecord` /
  `MistakeEntry` / `WordProgress`。全部 `@TableName` + `@TableId(AUTO)`；
  时间列标 `@TableField(fill=INSERT / INSERT_UPDATE)`（G2 新约束的兑现）；
  6 个业务表带 `@TableLogic deleted`；`user_word_book` 无 `deleted`（物理删除）。
- `mapper/`：7 个 `BaseMapper<T>` 接口 + `StatsMapper`。
- `resources/mapper/StatsMapper.xml`：热力图 / 掌握度聚合（每个查询**手写 `deleted = 0`**）。
- `config/JacksonConfig.java`：JSR-310 全局格式（`yyyy-MM-dd HH:mm:ss` / `yyyy-MM-dd`）。
  必要性：`spring.jackson.date-format` **对 JSR-310 无效**，漏配则契约 §1.4 的
  `createTime` 会输出带 `T` 的 ISO 字符串。
- `dto/response/`：`HeatmapDay` / `MasteryPoint`（无参构造 + setter，供 MyBatis 映射）。
- 测试：`EntityMappingIntegrationTest`（17）+ `test/resources/schema-h2.sql`。

**关键设计取舍（本阶段新增，已记入 design.md）**：
1. **统计的时间边界改由 Java 传入 `since` 参数**，不用 MySQL 专有的
   `DATE_SUB(CURDATE(), INTERVAL n DAY)`。原因：H2（MySQL 模式）不实现该函数，
   原写法导致 `mvn test` 报 `Function "date_sub" not found`。
   改为参数化后 **H2 与真实 MySQL 执行同一份 SQL**（消除方言偏差），
   且「回溯多少天」这条业务规则显式落在 Service 层、便于单测注入固定日期。
2. **热力图按 `check_in_date` 聚合**（非 `study_date`）：S3 §7.5 + 需求清单 §5.1 +
   接口详细设计 §7.1 三处一致。语义：**复习不打卡**，但属于学习活动，
   故复习只体现在掌握度（按 `study_date`）中。
   （此项修正了 design.md 草稿里误用 `study_date` 的写法。）
3. `mistake_entry` 的 `deleted` 仍是 `NOT NULL DEFAULT 0`（业务标志位，非时间戳），
   与「时间列去 DB 默认值」的 G2 决策不冲突。

**发现的真实缺陷（已修复）**：JDBC URL 中写 `characterEncoding=utf8mb4`。
`Connector/J` 用 `Charset.forName()` 解析该参数，而 `utf8mb4` **不是合法 Java 字符集名**
（实测 `FAIL utf8mb4`），连接直接失败：`Unsupported character encoding 'utf8mb4'`。
阶段 2 起潜伏至今，因从未有代码真正连库而未暴露。
已改为 `characterEncoding=UTF-8`（Connector/J 8 会自动映射到服务端 utf8mb4，
中文/emoji 正常）——涉及 `application.yml` / `application-local.yml` /
`application-local.yml.example` 三处。

**另外修复两处与阶段 4 无关但同期暴露的隐患**：

3. **`JwtUtilTest.tamperedTokenReturnsNull` 是 flaky 测试**（~6% 概率误报，阶段 2 遗留）：
   原断言篡改 token 签名段**最后一个字符**。32 字节 HMAC 的 base64url 签名共 43 字符
   （258 位 > 数据 256 位），末字符低 2 位是**填充位、解码时被丢弃**——
   改这些位得到的签名字节与原串完全相同，校验自然通过。
   实测模拟 2000 次：**6.0% 概率改写后签名不变**。
   已改为篡改 payload 首字符（签名输入必然变化，与随机字节无关），
   并加 `assertNotEquals(token, tampered)` 钉住「确实改了」。
   `JwtUtilTest` 连跑 12 次全绿（修复前约 6% 概率失败）。
4. **`mapper/`、`db/`、`resources/mapper/` 下的 `.gitkeep` 冗余且会打入 jar**：
   这些目录已有真实内容，`.gitkeep` 已删（产物里确认不再出现）。

**为何 `mvn test` 通过还不足以证明本阶段**：`mvn test` 跑在 H2 + **手写的**
`schema-h2.sql` 上，若手抄稿与 `V001/V002` 漂移，测试会在「与生产不同的结构」下全绿。
故本阶段额外做了**两重独立于 JUnit 的验证**（均可重复执行）：

| 脚本 | 作用 | 结果 |
| --- | --- | --- |
| `backend/scripts/verify-schema-entities.py` | 解析 Java 实体源码 ↔ 直连真实 MySQL `INFORMATION_SCHEMA`，**双向**核对列名；检查 `fill` 标注、`deleted`/`@TableLogic` 一致性、NOT NULL 无默认值列是否都有实体字段承载 | ✓ 7 实体 / 67 列全部一致 |
| `backend/scripts/verify-h2-schema-parity.py` | `schema-h2.sql` ↔ `V001/V002` 结构核对（表/列/类型/可空性/唯一约束） | ✓ 无漂移 |

以及一个面向**真实 MySQL** 的集成测试（默认跳过，需显式开启）：
`mvn -o test -Dmysql.it=true -Dtest=RealMysqlMappingIT -DfailIfNoTests=false`
——在真实 MySQL（`STRICT_TRANS_TABLES` + 时间列无默认值）上插入不设时间戳的实体，
**证明 `@TableField(fill=...)` 确实兜住了 `1364`**；并验证热力图/掌握度 XML
在 MySQL 方言下同样可执行。测试类 `@Transactional`，结束后精确计数 7 表 = 0。

**闸口验证实测**：
- `mvn -o clean test`：**56 测试全绿**（阶段 2 的 37 + 本阶段 19）。
- `RealMysqlMappingIT`：真实 MySQL 上 7 表插入/读回/聚合/**update 刷新 updateTime** 全通过。
- 掌握度聚合断言**变异测试已验证其有效**：把错题关联表故意改成 `word_progress`，
  新断言立即报错（证明不是「只断言 size()」的空壳测试）。
- 事务回滚核实：7 表精确 `COUNT(*)` 全为 0（无数据残留）。
- 红线：`anime_tracker` 仍 20 表，未被触碰。

## 5. 认证模块（阶段闸口：5 个 auth 接口自测通过）—— **已完成（118 测试全绿）**

15. [x] `AuthService`：注册（格式校验 + 唯一性 + BCrypt）、登录（失败 1002 → WARN 日志不含密码；禁用 1003）、微信登录（`code2Session`，真实 HTTP 调用微信 API；openid 不存在则建用户返回 `isNewUser:true`）、`profile` 查询（含 `totalCheckInDays`）、`profile` 更新（nickname ≤20、avatar 白名单 `avatar_1..6`）
16. [x] `AuthController` + 4 个 request DTO + 3 个 response DTO
17. [x] JWT 签发（userId/role/7 天）与 `JwtInterceptor` 全链路生效

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
- [x] `AuthServiceTest`：注册重复用户名、密码强度、nickname 长度、avatar 白名单

**阶段 5 实施记录（已完成，118 测试全绿）**：

- **产出文件**：
  - `dto/request/`：`RegisterRequest` / `LoginRequest` / `WxLoginRequest` / `UpdateProfileRequest`
  - `dto/response/`：`RegisterResponse` / `UserInfoResponse` / `LoginResponse` / `ProfileResponse`
  - `client/WxApiClient`：`code2Session` 真实调用（专用 `wxRestTemplate`，5s 超时）
  - `config/UserStatusValidator`：三态枚举（`ACTIVE` / `DISABLED` / `UNKNOWN`）
  - `config/UserStatusValidatorImpl`：每请求主键查库（`selectById`，`@TableLogic` 自动附 `deleted=0`）
  - `config/JwtInterceptor`：从原单方法 `isActive(Long)` 改为三态 `check(Long)`，区分 1003 与 1006
  - `service/AuthService` + `service/impl/AuthServiceImpl`
  - `controller/AuthController`（5 个接口，均只调一个 Service 方法 + 包 `Result`）

- **关键设计取舍**：
  1. **`UserStatusValidator` 从单方法升为三态枚举**：原 `isActive(Long)` 无法区分「账号被禁用（用户应找管理员）」与「账号不存在/已注销（应引导重新登录）」。两者都是 401，但 1003 与 1006 文案不同，混用会误导已注销账号的持有者去找管理员。
  2. **每请求查库校验 `status`**（而非信任 token）：这是 §8.7「禁用后下次请求立即失败」的唯一实现方式。一期 QPS 低，一次主键查询毫秒级；代价是每个受保护请求多一次 DB 查询，换来演示关键路径的确定性。
  3. **登录失败不区分「用户不存在」与「密码错误」**（均 1002）：避免用户枚举。微信用户（`password_hash` 为 NULL）走密码登录也归入 1002，且不得 NPE。
  4. **唯一性靠数据库唯一索引兜底**，而非仅靠「先查后插」：并发注册/并发微信建号都会撞唯一索引，捕获 `DuplicateKeyException` 后转为同一语义（登录重查复用；注册报 1001）。检查只是友好提示，索引才是正确性保证。
  5. **微信账号 `username` / `password_hash` 均为 NULL**：MySQL 唯一索引允许多个 NULL，不影响 `uk_username`；也从数据层保证微信账号无法走密码登录。
  6. **写入的 userId 一律取自 `UserContext`（token）**：`profile` / `updateProfile` 绝不接受请求参数传入 userId，无越权写入风险。

- **实测验证（可重复）**：
  - `mvn -o clean test`：**118 测试全绿**（阶段 2 的 37 + 阶段 4 的 19 + 本阶段新增 62），连跑 3 次稳定；
  - 新增测试：`client/WxApiClientTest`（8，起真实本地 HTTP 桩服务）、`service/AuthServiceImplTest`（21，真实库 + 并发分支桩注入）、`controller/AuthControllerIntegrationTest`（25，走 MockMvc 真实 HTTP）；
  - 改造两个阶段 2 遗留测试类（`GlobalExceptionHandlerIntegrationTest` / `JwtInterceptorIntegrationTest`，原先伪造 userId=999 签 token，如今会被真实查库判为 `UNKNOWN` → 401，故改为先建真实用户），并顺势锁定「禁用立即生效」与 `UserContext` 不串号；
  - **真实 MySQL + 真实微信 API 闸口冒烟**：`java -jar` 起真实服务，`/c/tmp/stage5-smoke.sh` **38/38 全过**；
    - 微信 `code2Session` 走真实网络：无效 code 返回 `errcode=40029` → 应用转为 1004（证明 appid/secret 已被微信接受）；
    - `§8.7` 禁用即时生效：直接 `UPDATE user SET status=0` 后，**同一 token 立即** → HTTP 401 + 1003；同一账号重新登录也 → 1003；
    - 响应体 UTF-8 逐字节校验：1002 文案字节 `e794a8...e8afaf` 与期望完全一致（中文不乱码）；
    - `createTime` 输出 `2026-10-16 18:11:07`（空格分隔，非 ISO 的 `T`）；
  - 红线自查：`main` 下无 `System.out` / `printStackTrace` / 硬编码凭证；`application.yml` 中 appid/secret 均为 `${WX_APPID:}` / `${WX_SECRET:}` 占位符；真实凭证仅在 `application-local.yml`（`.gitignore` 已验证排除）；
  - 红线比对：`anime_tracker` 仍 20 表，`ai_vocab` 7 表，冒烟数据已清理（`user` 表回到 0 行），**无非 BCrypt 密码哈希**。

## 6. 词库模块 + 种子导入（阶段闸口 G3：三本词书入库且计数正确）—— **已完成**

18. [x] `WordImportService.parseLine(String, Long)`（design.md §7.1，**唯一映射实现**，双入口共用）
19. [x] `WordBookService`：`listBooks(category, userId)`（含 `selected` / `learnedCount`）、
    `bookDetail(bookId, page, size)`、`select`、`deselect` + 3 个管理端方法占位（阶段 10 接 Controller）
20. [x] `BookController`（`/api/client/books`）+ `BookResponse` / `BookDetailResponse` /
    `WordResponse` / `BookSelectResponse` / `BookLearnedCount` DTO
21. [x] `VocabSeedRunner`（`@ConditionalOnProperty(vocab.seed=true)`，从 `data/vocab-source/` 读，
    按 name+category 幂等跳过，导入后断言行数 == 期望值）
22. [x] 执行种子导入（真实 MySQL）

**实施调整（相对原计划）**：
- 批量写入**不用 `saveBatch`**：实测 MyBatis-Plus 3.5.9 的
  `Db.saveBatch(coll, 1000)` 是**循环单条 insert**（字节码已核实），
  22761 行 = 22761 次往返。改为自写 `WordMapper#insertBatch` + XML
  多行 `VALUES` 单语句（每批 1000）。XML 绕过 `MetaObjectHandler`，
  故显式 `NOW()` + `deleted=0`。详见 design.md §7.3。
- **词书创建时机下移**：`importFromStream` 延迟到首个合法行才 `insert` 词书。
  原实现先建词书再解析，导致「整份文件全坏行」抛 2004 后
  库里残留一本 0 词空壳（`@Transactional` 只在调用方也开事务时回滚）。
- `correctWordCount` 用 `COUNT(*)` 回写，`ImportResult.success` 同源取该值。
- 逐行导入**不做单词去重**（需求要求 `word_count` == 文件行数；源数据本身含重复词）。
- 列表 `learnedCount` / `selected` 各一次查询取全，规避 N+1（design.md §7.5）。

**验证（真实 MySQL + 真实 HTTP，非 mock；实测记录）**：

*G3 闸口 — 种子导入（`--vocab.seed=true`）*：
```
word_book: (6,'四级核心词汇','CET4',7508,0) (7,'六级核心词汇','CET6',5651,0) (8,'考研核心词汇','KAOYAN',9602,0)
每本 COUNT(*) 实际单词数:  6→7508   7→5651   8→9602       （word_count == actual，全部一致）
word 总行数: 22761 = 7508+5651+9602
```
- **幂等**：第二次运行输出「种子词书已存在，跳过」×3，`word_book` 仍为 3 行、`word` 仍为 22761 行。
- **时间列**：`SELECT COUNT(*) FROM word WHERE create_time IS NULL OR update_time IS NULL` → **0**
  （证明 XML 里的显式 `NOW()` 生效）。
- **映射抽查**：`abruptly / ə'brʌptlɪ / adv. 突然地 / "The path ends off abruptly."`；
  `definition` / `audio_url` 全为 NULL（一期留空）；空音标数 CET4 66 / CET6 50 / KAOYAN 17、
  空例句 164/131/205，与源文件缺失统计逐一对上。
- **红线**：`anime_tracker` 仍 20 表，全程未触碰。

*契约 §2 四接口端到端（真实 MySQL，curl）*：

| 用例 | 结果 |
|---|---|
| `GET /books` 未选时 | 三本全返回，`selected=false` / `learnedCount=0` ✅ |
| `GET /books?category=CET6` | 只返回 1 本 CET6 ✅ |
| `GET /books?category=cet4` | 小写同样命中（大小写不敏感）✅ |
| `GET /books?category=TOEFL` | `code=0` + `data=[]`（非法分类不报错，为空集）✅ |
| `GET /books/{id}?page=1&size=3` | `total=5651 / page=1 / size=3`，3 条词数据完整 ✅ |
| `GET /books/999999` | `2001 词书不存在` ✅ |
| `POST /books/7/select` | `{bookId:7, selected:true}` ✅ |
| 重复 select | `2003 词书已加入` ✅ |
| select 不存在的书 | `2001` ✅ |
| select 后列表 | 该书 `selected=true` ✅ |
| 造 1 条 `word_progress` 后列表 | `learnedCount=1` ✅（`GROUP BY` 聚合生效） |
| `DELETE /books/7/select` | `{selected:false}` ✅ |
| 再次 deselect | `2002 未选择该词书` ✅ |
| **移出词书后历史保留** | `word_progress` 仍 1 行、`user_word_book` 关联行已删 ✅（契约明文要求） |
| 分页边界 `page=0&size=0` | 回落 `page=1 size=20` ✅ |
| 分页 `size=3000` | 被 `maxLimit=500` 截为 500 ✅ |
| `page=99999` | `total` 正确、`list=[]` ✅ |
| 普通用户访问 `/api/admin/**` | HTTP 403 + `1007 无权访问该资源` ✅ |
| 无 token | HTTP 401 + `1006 登录状态已失效，请重新登录` ✅ |
| 中文 UTF-8 逐字节校验 | 响应中词书名 hex 与 `四级核心词汇` 的 UTF-8 编码**完全相同** ✅ |

*测试*：`mvn -o clean test` → **143 tests, 0 failures, 0 errors**（阶段 5 为 118，本阶段 +25：
`WordImportServiceImplTest` 11 例 + `BookControllerIntegrationTest` 14 例）。

*G3 闸口结论*：**通过**（`e140517`）。三本词书计数 7508/5651/9602 严格一致、二次运行幂等；
`word` 时间列 NULL 计数 0；HTTP 冒烟 20 用例全过。详见 `research/05-g3-stage6-vocab-review.md`。

## 7. 学习 + 复习 + 错题（阶段闸口 G4：核心闭环端到端跑通）—— **已完成（231 测试全绿；G4 已过，`76cf216`）**

23. [x] `StudyService.plan(userId)`：按 design.md §4.1 生成（复习词在前、每词书 20 新词、`learned` 标记）
24. [x] `StudyService.record(userId, req)`：写 `word_progress`（upsert）、`known=false` 立即收录错题、首次学习设置 `stage=1, next_review_date=+1天`
25. [x] `StudyService.checkIn(userId)`：design.md §4.2（3001 幂等 / 3002 / 3003 / streak 计算）
26. [x] `StudyService.streak(userId)`
27. [x] `ReviewService.today(userId)`：`next_review_date <= CURDATE() AND learned=1` 且未掌握
28. [x] `ReviewService.record(userId, req)`：design.md §4.3（答对 stage+1 封顶 4；答错 stage=1、`wrong_count`+1、确保收录错题）
29. [x] `MistakeService`：`list`（分页 + `mastered` 筛选）、`markMastered`（4001/4002）、`remove`（逻辑删除，4001）
30. [x] 三个 Controller + DTO

**验证（含算法单测）**：
- [x] `StudyServiceTest`：streak 连续 +1；中断重置为 1；同日重复打卡返回 3001 且无重复行
- [x] `ReviewServiceTest`：连对 4 次 stage 依次 2/3/4/4；答错后 stage=1 且 `next_review_date`=明天
- [x] `MistakeServiceTest`：同词两次错误 → 一条记录 `wrong_count=2`；标记已掌握后再次答错 → `mastered` 回到 0
- [x] 手工全链路脚本：`g4-smoke.py`（26 PASS）+ `g4-crossday.py`（18 PASS），真实 MySQL + 真实 HTTP，覆盖上条全部断言

**阶段 7 实施记录（已完成，231 测试全绿；G4 已过）**：

- **产出文件**：
  - `dto/response/` 11 个（`StudyWordResponse` / `StudyPlanResponse` / `StudyRecordResponse` / `CheckInResponse` / `StreakResponse` / `ReviewWordResponse` / `ReviewTodayResponse` / `ReviewRecordResponse` / `MistakeResponse` / `MistakeMasteredResponse` / `MistakeRemoveResponse`）；`dto/request/` 2 个（`StudyRecordRequest` / `ReviewRecordRequest`，`@NotNull`）
  - `config/ClockConfig`（`Clock.systemDefaultZone()` Bean，使「今天」可注入固定日期）、`common/Pages`（分页参数归一化，`WordBookServiceImpl` 一并重构复用）
  - `service/ReviewSchedule`（纯函数 `nextStage` / `intervalDays` / `nextReviewDate`，常量 `REVIEW_INTERVALS=[1,3,7,15]`）
  - `service/` + `impl/`：`StudyService` / `ReviewService` / `MistakeService`
  - `mapper/WordMapper#selectUnlearnedNewWordIds`、`mapper/WordProgressMapper#selectPlanReviewWords/#selectDueReviewWords` + `resources/mapper/WordProgressMapper.xml`（NEW，4 表 JOIN，手工 `deleted=0`）、`StatsMapper` 三个计数
  - `controller/`：`StudyController` / `ReviewController` / `MistakeController`（薄层，userId 取 `UserContext`）
  - 测试：`ReviewScheduleTest`(8) / `StudyServiceImplTest`(24) / `ReviewServiceImplTest`(16) / `MistakeServiceImplTest`(23) / `StudyFlowControllerIntegrationTest`(17)；测试支撑 `testsupport/FixedClock` + `TestClockConfig`（`@Primary` 覆盖生产 Clock）

- **修 2 个真缺陷（均由测试先失败暴露）**：
  1. **「做完反而不能打卡」（缺陷 A）**：原计划按实时推导生成，词上报后逐词从计划消失，全部做完 `total=0`，`checkIn` 抛 3002。修复：计划语义改为「今日任务集合」——追加第三段「今天已上报过的词」（`todayReportedWordTypes` 按流水 id 升序返回 `LinkedHashMap<wordId,studyType>`），使 `total` 恒定、`finished` 单调递增。**该修复已回写契约 §3.1（V1.1）**。
  2. **计划候选集后滑（缺陷 B）**：`selectUnlearnedNewWordIds` 判据原为 `update_time < #{dayStart}`，而 `update_time` 每次复习都会被刷新 → 历史词被误判为「新词」。改为 `create_time < #{dayStart}` 且 `learned=1`（`create_time` 仅首次进入计划时写入）。

- **G4 闸口实测（真实 MySQL + 真实 HTTP，非 mock）**：
  - 打包 `target/vocab-backend-1.0.0.jar` 30,702,336 B；服务 8080 启动 4.827s，日志 ERROR=0。
  - `g4-smoke.py` **26 PASS / 0 FAIL**（§2/§3/§4/§5 正常 + 错误路径，含无 token→401+1006、§3.1 计划窗口不漂移、§5.1 `mastered` 为 JSON 布尔）。
  - `g4-crossday.py` **18 PASS / 0 FAIL**（自包含跨天）：D1 打卡 streak=1 → 回拨一天 → D2 昨天 20 词成复习词（overlap=20）、今天新词下一批（overlap=0）、计划 total=40、跨天 streak=2；直连 MySQL 确认 stage 1→2 间隔 3 天、3→4 跳 15 天、答错 stage 退回 1 且次日到期、stage=4 封顶。
  - 数据完整性：`learned=1 && next_review_date IS NULL`=0；`stage` 越界=0；`mistake_entry`/`study_record`/`user_word_book` 三类唯一约束重复组=0。
  - **红线**：`anime_tracker` 仍 20 表，未触碰。
  - 全量回归 `mvn -o test` → **231 tests, 0 failures, 0 errors**。详见 `research/07-g4-gate-record.md`。

> ⚠ 经验教训（夹具错误，非产品缺陷）：`study_record.study_date` 与 `check_in_date`
> 在真实数据里**恒等**（打卡时同时写入），跨天夹具回拨日期必须**成对回拨**；
> 只改其一会造成 `lastCheckInDate` 的假失败。

## 8. AI 中转与降级（阶段闸口 G5：ai-service 未启动时降级路径全绿）—— **已完成（250 测试全绿；G5 已过，`45eb7d8`）**

31. [x] `AiService`：`example` / `explain` / `report`，各自超时 15/30/60s（`RestTemplateBuilder` 每次构造专用实例）
32. [x] `AiMetrics`：内存计数器 + 环形降级事件队列（容量 100）
33. [x] `AiController` + DTO
34. [x] **`example` 降级不抛异常**（HTTP 200 + `code:"0"` + `degraded:true` + `word.example`）——代码中加显式注释
35. [x] `report` 前置：学习天数 < 3 → 5003 且**不发起 HTTP**
36. [x] `explain` 降级 → 抛 5001/5002

**验证（关键闸口）**：
- [x] 确保 ai-service **未启动**，跑：
  - `POST /api/client/ai/example` → `code:"0"`、`degraded:true`、`example` 非空（来自词库）✅
  - `POST /api/client/ai/explain` → `code:"5002"` ✅（连接被拒属「服务异常」非超时）
  - `POST /api/client/ai/report`（学习天数 <3）→ `code:"5003"` ✅
  - `POST /api/client/ai/report`（学习天数 ≥3）→ `code:"5002"` ✅
- [x] **同时**验证学习/打卡/复习完全不受影响（重跑阶段 7 的 `g4-smoke.py` → 26 PASS）
- [x] `AiServiceTest`：**用真实对端而非 Mock 伪造故障**——`127.0.0.1:1`（连接被拒→5002）、`SilentHttpServer`（接受连接但永不响应→真实读超时→5001）、`StubAiServer`（真实 HTTP 校验字段名）。Mock 复现不出真实异常类型。

**阶段 8 实施记录（已完成，250 测试全绿；G5 已过）**：

- **产出文件**：`dto/request/{AiExampleRequest,AiExplainRequest}`；`dto/response/{AiExampleResponse,AiExplainResponse,AiReportContent,AiReportResponse}`；`ai/{AiMetrics,AiCallException,AiServiceClient}` + `ai/dto/` 6 个内部调用 DTO；`service/AiService` + `impl/AiServiceImpl`；`controller/AiController`；测试类 `AiServiceImplTest`(13) / `AiControllerIntegrationTest`(6) + 测试支撑 `SilentHttpServer` / `StubAiServer`。`Constants` 增 `REPORT_RECENT_DAYS=14` / `REPORT_TOP_MISTAKES=10`。
- **修 1 个真缺陷（测试先失败）**：`AiServiceClient#isTimeout()` 原只判 `java.net.SocketTimeoutException`。实测 Spring Boot 3.5 默认 `JdkClientHttpRequestFactory` 抛的是 `java.net.http.HttpTimeoutException`，与前者是**兄弟类（互不继承）** → **生产默认配置下所有真实超时都会被误判为 5002**。修复为同时判两类超时，并加 Javadoc「超时判定的唯一真相源」。回归防护：`explainThrows5001OnRealTimeout` + `explainThrows5002WhenServiceDown`。
- **G5 闸口实测（ai-service 刻意未启动 + 真实 MySQL + 真实 HTTP）**：
  - 打包 `target/vocab-backend-1.0.0.jar` 30,728,971 B；服务 8080 启动 6.392s，ERROR=0；`netstat` 确认无 8000/8001 监听。
  - `g5-smoke.py` **24 PASS / 0 FAIL**（A/B/C/D 四组：example 降级不报错、explain→5002、report 天数<3→5003、AI 不可用不影响打卡/学习/复习）。
  - 真实超时路径：`silent-ai-server.py`（8000 端口只监听不响应）+ 后端压短超时 1200ms → `explain` 得 **5001**（非 5002），日志 `WARN AI 降级 feature=explain reason=TIMEOUT`。
  - 敏感信息核查：AppSecret / MySQL 密码 / AppID / JWT 明文密钥 / 用户明文密码 全 0 命中。
  - 全量回归 `mvn -o test` → **250 tests, 0 failures, 0 errors**。详见 `research/08-g5-stage8-ai-review.md`。
- **遗留待办（非本阶段引入，记入阶段 11）**：`application.yml` 生产默认 `com.vocab.mapper: DEBUG` 会打印 `password_hash` 的 BCrypt 哈希 → 生产默认改 `INFO`，`mapper` DEBUG 仅保留在本地 profile。
- **范围澄清**：`ai-stats` 管理端点**未在本阶段开工**，判定属阶段 10（见 §10 第 45 项）。

## 9. 统计模块（阶段闸口：SQL 聚合正确，无内存加载）—— **已完成（277 测试全绿，`a8be043`）**

37. [x] `StatsMapper.xml`：热力图按 `check_in_date` 聚合近 12 周；掌握度按天聚合近 30 天
38. [x] `StatsService`：补零日期序列（84 天连续）、`level` 分档（`0/1–10/11–20/21+`）、`masteryRate` 取整
39. [x] `StatsController` + DTO

**验证**：
- [x] 造多天数据后，`GET /stats/heatmap` 返回 84 个点、日期连续、`level` 与 `wordCount` 匹配分档（边界值 1/10/11/20/21 逐一验证）
- [x] `GET /stats/mastery?days=30` 返回 30 个点，`masteryRate` 与手工 SQL 计算一致
- [x] 确认 `StatsMapper.xml` 中**没有**加载原始记录到 Java 的写法（`GROUP BY` 后 Java 只做「补零 + 分档」）

**阶段 9 实施记录（已完成，277 测试全绿）**：

- **产出文件**：`dto/response/{HeatmapResponse,MasteryResponse}`；`common/MasteryRates`（**唯一真相源**：`percent(mastered,total)`，`total<=0 → 100`；统计曲线 §7.2 与 AI 报告 §9.3 共用，`AiServiceImpl#buildStudyStats` 一并改调它）；`service/StatsService` + `impl/StatsServiceImpl`（只做「SQL 聚合 + Java 补零/分档」）；`controller/StatsController`；`Constants` 增 `DEFAULT_HEATMAP_WEEKS=12` / `DEFAULT_MASTERY_DAYS=30` / `MAX_HEATMAP_WEEKS=52` / `MAX_MASTERY_DAYS=365`；测试 `StatsServiceImplTest`(14) / `StatsControllerIntegrationTest`(9) / `MasteryRatesTest`(4)。
- **修 1 个真问题（规则一致性违反）**：`StatsMapper.xml#selectMasteryDaily` 原用 `DATE(create_time)`——**MySQL 专有函数**，违反「时间边界由 Java 传参」红线（同文件头注释自己就声明了该规则；H2 `MODE=MySQL` 恰好支持 `DATE()`，故测试全绿而漏检）。改为 SQL-92 `CAST(create_time AS DATE)`。
- **实测**：`stage9-stats-smoke.py` **29 PASS / 0 FAIL**（真实 MySQL 8.4.9 + 真实 HTTP，自包含，只造 `stage9*` 用户）；直连 pymysql 验证 `CAST(create_time AS DATE)` 在 MySQL 8.4.9 正确返回日期；回归 `g4-smoke.py` 26 PASS / `g4-crossday.py` 18 PASS / `g5-smoke.py` 24 PASS，全 0 FAIL；全量 `mvn -o test` → **277 tests, 0 failures, 0 errors**。**红线** `anime_tracker` 仍 20 表。详见 `research/09-g6-stage9-stats-review.md`。

## 10. 管理模块（阶段闸口 G6：先改契约文档，再改代码）—— **G6 已过（`29e8e5f`）；阶段 10 实施完成，待 G7**

40. [x] **先回写 `docs/接口详细设计.md`**：
    - §8 新增 8.8 `GET /api/admin/books`、8.9 `DELETE /api/admin/books/{bookId}`、8.10 `GET /api/admin/monitor/ai-stats`（含字段表与示例）
    - §10 变更记录新增 V1.1 行（说明补齐的管理端接口 + `word_progress` 表 + `study_record` 加固唯一索引）
    - 单独提交：`docs(contract): 补齐管理端接口并记录 word_progress 偏离`
    - **实际范围**：`docs/接口详细设计.md` V1.0→V1.1（20 处替换，390→498 行）+ `docs/S3-软件概要设计说明书-正文.md` V1.3（5 处替换）。落盘脚本 `.tmp/g6_contract.py` + `.tmp/g6_s3.py`，**每处断言「恰好命中一次」**（文本漂移即 `FAIL ... 命中 N 次` 并 `exit(1)`），避免静默漏改。
    - 提交 `29e8e5f`（**仅 2 文件**，用 `git add --pathspec-from-file=.tmp/g6_paths.txt` 精确 add）。
41. [x] `AdminBookController` 导入：解析计数 → `saveBatch` → 校正 `word_count` → `{bookId, success, skipped}`；整体非法 → 2004（复用 `WordBookService.importBook`，Controller 只做 multipart→InputStream 与边界异常翻译）
42. [x] `AdminWordController`：分页查询（bookId/keyword）、新增（`wordCount+1`）、修改、删除（逻辑删除，`wordCount−1`）
43. [x] `AdminUserController`：分页 + keyword 搜索、禁用/启用（**ADMIN 不可禁用 → 1005**）、操作 INFO 日志（操作者 id、动作、目标用户）
44. [x] `AdminBookController`：`GET /api/admin/books`、`DELETE /api/admin/books/{bookId}`
45. [x] `AdminMonitorController`：`GET /api/admin/monitor/ai-stats`
46. [x] 全部 `/api/admin/**` 校验 ADMIN（普通用户 → 403）—— 复用阶段 5 `JwtInterceptor` L116-117，未改动拦截器

**验证**：
- [x] 普通用户 token 访问任一 `/api/admin/**` → HTTP 403（`AdminControllerIntegrationTest` A1 + 冒烟 A1）
- [x] 导入一份**构造的含坏行**JSONL（3 行，1 行缺 `word`）→ 响应 `success:2, skipped:1`（冒烟 C0）
- [x] 单词新增/删除后 `word_count` 正确 ±1（集成 §8.3/§8.5 + 冒烟 D3/D10；另用 `AdminServiceImplTest` 断言「5 增 5 删精确回 0，无丢更新」）
- [x] 禁用某用户 → 该用户 token 立即 401 + 1003；禁用 ADMIN → 1005（集成 + 冒烟 E6/E8）
- [x] `ai-stats` 能看到阶段 8 产生的降级计数（冒烟 F6：真实触发一次 AI 降级后 `degradedCount` +1）

**阶段 10 实施记录（已完成，326 测试全绿）**：

- **产出文件**：
  - 请求 DTO：`WordUpsertRequest`（§8.3/§8.4 共用，仅 `@Size`）、`UserStatusRequest`（`Integer` + `@NotNull`，刻意防漏传被默认 0 误读为「禁用」）
  - 响应 DTO：`AdminUserResponse`、`AdminBookResponse`、`AiStatsResponse`、`AiFeatureStat`、`DegradeEventView`、`WordDeleteResponse`、`BookDeleteResponse`、`UserStatusResponse`、`UserCheckInCount`（内部投影，避 N+1）；**均独立成类，不复用实体/内部对象**
  - `common/SuccessRate`（**新抽出的唯一真相源**：`percent(success,total)` 分母**含降级**、无调用取 100）；`AdminServiceImpl#aiStats` 改调它
  - `service/AdminService` + `impl/AdminServiceImpl`（单词 CRUD / 用户列表 / 启禁 / AI 监控）
  - `controller/admin/{AdminWordController,AdminUserController,AdminBookController,AdminMonitorController}`
  - `WordBookService.adminListBooks()` 返回类型由 `List<BookResponse>` 收紧为 `List<AdminBookResponse>`（管理端需 `createTime`、不需 `selected`/`learnedCount`）
  - `StatsMapper#countCheckInDaysByUsers` + XML（一次 `GROUP BY user_id` 批量取打卡天数）
  - 测试：`AdminControllerIntegrationTest`(31)、`AdminServiceImplTest`(13)、`SuccessRateTest`(5)
- **开工时修 1 个真缺陷（参数语义混淆）**：`createWord` 原先直接 `requireBook(bookId)`，`bookId == null` 时 `requireBook(null)` 会先撞空指针/落进 2001；新增显式 `bookId == null → 1005` 前置判定，使「少传字段(1005)」与「词书不存在(2001)」两条语义分离（`AdminServiceImplTest#createWordDistinguishesMissingAndNonexistentBook` 锁死）。
- **`word_count` 用 SQL 原子自增/减**：`setSql("word_count = word_count + (delta)")` + 显式 `set(updateTime, now(clock))`。因 `entity=null` 的 `update(null, wrapper)` 不触发 `MetaObjectHandler`，不手填会留下「行改了时间没变」脏数据；用注入的 `Clock` 而非 SQL `NOW()`，与「时间边界由 Java 传参」红线一致。
- **`ai-stats` 口径**：`successRate` 分母含降级（否则永远 100%），`avgCostMs` 含降级、无调用 0，`recentEvents` 最新在前最多 100，`features` 固定 example/explain/report 顺序。
- **测试自身错 3 处（已修）**：`AdminServiceImplTest.deleteWordIsLogical` 夹具初值设 1 却又 +1 → 改初值 0；`assertEquals(long,int)` 重载歧义 → `(long)` 强转；`AiMetrics` 是单例内存计数器 → `aiStats` 断言一律用「前后快照差值」而非绝对值。
- **实测**：`stage10-admin-smoke.py` **46 PASS / 0 FAIL**（真实 jar + 真实 MySQL 8.4.9 + 真实 HTTP，46 用例 A0–H1，`DBPASS` 环境变量，独立前缀 `g6adm_`/`g6usr_`/`G6冒烟`，末尾按前缀精确物理清理）；回归 `g4-smoke.py` 26 / `g4-crossday.py` 18 / `g5-smoke.py` 24 / `stage9-stats-smoke.py` 29 全 0 FAIL；全量 `mvn -o test` → **326 tests, 0 failures, 0 errors**；服务日志 ERROR=0；**红线** `anime_tracker` 仍 20 表（真库现有 26 用户 / 22761 词 / 3 词书，冒烟后无 `g6*` 残留）。详见 `research/10-g6-stage10-admin-review.md`。

**阶段 10 开工时的前置条件**（编码前侦察结论，现已全部满足）：
- `JwtInterceptor` 管理端角色拦截**阶段 5 已落位**（`path.startsWith("/api/admin/") && role != ADMIN` → HTTP 403 + 1007），本阶段**未改动拦截器**，只建 Controller 端点。副作用见下。
- `WordBookService` 三个管理端方法已于阶段 6 实现，本阶段接 Controller：`adminListBooks()` / `importBook(InputStream,name,category)` / `deleteBook(Long)`。
- 契约口径冻结于 V1.1 §8（10 接口 = 7 契约原稿 + 3 补齐），编码严格照此执行。
- **测试连带影响（已修）**：管理端 Controller 落地后 `/api/admin/users` 由「404 证明已过鉴权」变为「200 命中真实 Controller」，`JwtInterceptorIntegrationTest` 中两处硬编码 404 断言随之失效。改为语义断言「**非 401/403 即已过拦截器**」（常量 `AUTH_PASSED=200`），避免每新增一个管理端接口就误报一次。

## 11. 收尾（阶段闸口 G7：可交付）—— **未开始**

47. [ ] `backend/README.md`：环境要求、建库、脚本执行顺序、环境变量清单、种子导入、启动、自测方法
48. [ ] `scripts/smoke-test.sh`：一键跑通全部接口断言（含降级路径）
49. [ ] `mvn -q clean package` 全绿（含测试）
50. [ ] 自查 `prd.md` 的**每一条**验收标准，逐条打勾

> **阶段 11 额外待办**（阶段 8 G5 发现）：`application.yml` 生产默认
> `com.vocab.mapper: DEBUG` 会打印 `password_hash` 的 BCrypt 哈希
> → 生产默认改 `INFO`，`mapper` DEBUG 仅保留在本地 profile。

---

## 评审闸口（必须停下来人工确认）

| # | 位置 | 闸口内容 |
|---|---|---|
| G1 ✅ | 阶段 1 后 | 建库与词库落盘完成，行数正确 |
| G2 ✅ | 阶段 3 后 | 7 张表结构与 S3 §7 + design.md 一致 |
| G3 ✅ | 阶段 6 后 | 三本词书计数 7508/5651/9602 严格一致（已过，`e140517`） |
| G4 ✅ | 阶段 7 后 | 核心闭环端到端跑通 + 算法单测通过（已过，`76cf216`） |
| G5 ✅ | 阶段 8 后 | **降级路径在 ai-service 未启动时全绿**（一等约束）（已过，`45eb7d8`） |
| G6 ✅ | 阶段 10 前 | 契约文档已回写（先文档后代码，不可颠倒）（已过，`29e8e5f`） |
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

- [x] S3 §7 表数 6 → 7（`word_progress`）—— **已回写（S3 V1.3，`29e8e5f`）**，S3 L443 现为「核心表共 **7** 张」
- [x] S3 §7.5 `study_record` 补加固唯一索引说明 —— 已并入 S3 V1.3 实现补充段（L445）
- [x] S3 §7.1「关键索引」列补注 `uk_user_word_book_user_book` —— 已并入 S3 V1.3 实现补充段（L445）
- [x] S3 §7.2 补注：`create_time` / `update_time` 不带数据库默认值，由 MyBatis-Plus `MetaObjectHandler` 填充 —— 已回写（S3 L445）
- [ ] S3 §8.2 待确定问题登记 AI 供应商为 DeepSeek —— **仍待办**（S3 全文无 DeepSeek 字样）
- [x] `docs/接口详细设计.md` §8/§10（阶段 40 已完成，`29e8e5f`）
- [x] **`docs/接口详细设计.md` §0 错误码表**：补 `1006/1007/9002/9003/9004` 及「HTTP 4xx 保留给路由层错误」的边界说明 —— **已回写（V1.1）**，详见 `research/01-contract-gaps-stage2.md`

> 说明：索引名**已回退为 S3 §7.1 原样**（G2 复核决策），故**不再属于**待同步项——
> 脚本与 S3 已逐字一致（14/14 实测）。
