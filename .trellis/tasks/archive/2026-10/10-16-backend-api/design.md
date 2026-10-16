# backend 技术设计

> 本文件承载**技术设计**（边界、契约、数据流、权衡、兼容性）。需求与验收标准见 `prd.md`。

---

## 1. 边界

### 本服务负责

- 唯一 MySQL 访问方（架构一等约束）
- 全部业务规则：打卡连续天数、间隔复习调度、错题收录去重、计划生成
- JWT 签发与校验、BCrypt 哈希、角色鉴权
- **AI 调用编排与降级**（调用 ai-service，超时控制，降级兜底）
- 统计聚合（SQL 内完成）
- 统一 `Result<T>` 包装与错误码

### 本服务不负责

- AI 内容生成（ai-service）
- 任何前端渲染
- 数据库级外键约束（应用层维护）
- 文件存储/CDN（词库为一次性导入）

### 与 ai-service 的边界

```
POST http://<ai-service>/ai/v1/example  {word, translation, context}      → {example, exampleTranslation, mnemonic}   15s
POST http://<ai-service>/ai/v1/explain  {word, translation, wrongContext} → {explanation, advice}                    30s
POST http://<ai-service>/ai/v1/report   {studyStats:{...}}                → {overview, weakness, suggestion}          60s
```

- 单词文本**由后端查库后传入**，前端只传 id。
- ai-service 请求失败/超时 → 后端映射为 5001（超时）/ 5002（异常），并按接口决定降级行为。
- **后端是唯一知道 ai-service 地址的一方**（配置项 `ai.service.base-url`）。

---

## 2. 工程结构

```
backend/
├── pom.xml
├── README.md
├── scripts/
│   ├── fetch-vocab.sh          # 拉取三份 JSONL 到 ../data/vocab-source/
│   └── smoke-test.http / .sh   # 接口自测
└── src/main/
    ├── java/com/vocab/
    │   ├── VocabApplication.java
    │   ├── common/
    │   │   ├── Result.java             # {code,message,data} + ok()/fail()
    │   │   ├── ErrorCode.java          # 1xxx..9xxx 常量表（枚举）
    │   │   ├── BizException.java
    │   │   ├── PageResult.java         # {list,total,page,size}
    │   │   ├── Constants.java          # DAILY_NEW_WORDS=20, REVIEW_INTERVALS={1,3,7,15}
    │   │   └── GlobalExceptionHandler.java
    │   ├── config/
    │   │   ├── MybatisPlusConfig.java  # 分页插件 + MetaObjectHandler 自动填充
    │   │   ├── WebMvcConfig.java       # 注册 JwtInterceptor
    │   │   ├── JwtInterceptor.java     # 校验 token + status + /api/admin 角色
    │   │   ├── JwtUtil.java            # 签发/解析
    │   │   └── RestTemplateConfig.java # 带超时的 RestTemplate(ai-service)
    │   ├── entity/     User, WordBook, Word, UserWordBook, StudyRecord, MistakeEntry, WordProgress
    │   ├── mapper/     对应 7 个 Mapper（含统计用 XML）
    │   ├── dto/
    │   │   ├── request/  RegisterRequest, LoginRequest, WxLoginRequest, UpdateProfileRequest,
    │   │   │             StudyRecordRequest, CheckInRequest, ReviewRecordRequest,
    │   │   │             AiExampleRequest, AiExplainRequest, WordUpsertRequest, UserStatusRequest
    │   │   └── response/ ProfileResponse, LoginResponse, BookResponse, BookDetailResponse,
    │   │                 StudyPlanResponse, StudyPlanWord, CheckInResponse, StreakResponse,
    │   │                 ReviewTodayResponse, ReviewWord, ReviewRecordResponse,
    │   │                 MistakeResponse, AiExampleResponse, AiExplainResponse, AiReportResponse,
    │   │                 HeatmapResponse, HeatmapDay, MasteryResponse, MasteryPoint,
    │   │                 AdminUserResponse, ImportResult, AiStatsResponse
    │   ├── service/ + service/impl/   AuthService, WordBookService, StudyService,
    │   │                              ReviewService, MistakeService, AiService,
    │   │                              StatsService, AdminService, WordImportService
    │   └── controller/  AuthController, BookController, StudyController, ReviewController,
    │                    MistakeController, AiController, StatsController,
    │                    admin/AdminBookController, admin/AdminWordController,
    │                    admin/AdminUserController, admin/AdminMonitorController
    └── resources/
        ├── application.yml             # 无明文密钥，全占位符
        ├── application-local.yml       # gitignore（本地真实凭据）
        ├── db/V001__init.sql
        ├── db/V002__word_progress.sql
        └── mapper/StatsMapper.xml      # 热力图/掌握度聚合（Wrapper 无法表达）
```

---

## 3. 数据模型

### 3.1 变更点相对 S3

| 项 | S3 原文 | 本设计 | 理由 |
|---|---|---|---|
| 表数量 | 6 张 | **7 张** | 新增 `word_progress`，承载 stage/next_review_date（S3 无字段可放，见 research §3.1） |
| 复习列表 | 「计算生成，不设独立存储表」 | 改为「由 `word_progress` 查询生成」 | **仍然是计算生成、无独立"待复习列表"表**；`word_progress` 存的是每词进度状态，不是列表快照。语义上不违背原意，但表数确实变为 7。 |

### 3.2 新增表 DDL 设计

```sql
CREATE TABLE IF NOT EXISTS word_progress (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  user_id          BIGINT       NOT NULL,
  book_id          BIGINT       NOT NULL,
  word_id          BIGINT       NOT NULL,
  stage            TINYINT      NOT NULL DEFAULT 0 COMMENT '0=未进入复习,1..4=间隔阶段',
  next_review_date DATE         NULL     COMMENT '下次复习日期，NULL=不参与复习',
  learned          TINYINT      NOT NULL DEFAULT 0 COMMENT '1=已学过',
  wrong_count      INT          NOT NULL DEFAULT 0,
  deleted          TINYINT      NOT NULL DEFAULT 0,
  create_time      DATETIME     NOT NULL,
  update_time      DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_word_progress_user_word (user_id, word_id),
  KEY idx_word_progress_user_review (user_id, next_review_date),
  KEY idx_word_progress_user_book_learned (user_id, book_id, learned)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**唯一键 `(user_id, word_id)` 是整个设计的关键**：
- 学习上报 → `INSERT ... ON DUPLICATE KEY UPDATE` 或先查后写，天然幂等。
- 「未学单词」= `learned = 0`，直接命中 `idx_word_progress_user_book_learned`。
- 「今日待复习」= `next_review_date <= CURDATE() AND learned = 1`，命中 `idx_word_progress_user_review`。

> 注：`wrong_count` 在 `mistake_entry` 里也有。**`mistake_entry.wrong_count` 是权威值**（契约 §5.1 直接返回它）；`word_progress.wrong_count` 仅用于复习调度参考。写入时两者同步更新。这个冗余是刻意的，避免查询错题列表时联查两张表。

### 3.3 表名与保留字

MySQL 8 中 `USER` / `STATUS` / `ROLE` / `SOURCE` / `LEVEL` / `CORRECT` 经实测**均非保留字**（`INFORMATION_SCHEMA.KEYWORDS.RESERVED = 0`）。但为稳妥，DDL 与 XML 中表名/列名统一加反引号。

### 3.4 索引命名：严格照 S3 §7.1 原样（G2 复核后决策）

**最终决策**：索引名**逐字照 S3 §7.1「关键索引」列**，**不加表名前缀**。

| 表 | 索引名 | 类型 |
|---|---|---|
| user | `uk_username` / `uk_openid` | 唯一 |
| word_book | `idx_category` | 普通 |
| word | `idx_book_id` | 普通 |
| user_word_book | `idx_user_id` / `idx_book_id` / `uk_user_word_book_user_book` | 普通 ×2 + 唯一 |
| study_record | `idx_user_date` / `idx_user_book` / `uk_study_record_user_word_date_type` | 普通 ×2 + 唯一 |
| mistake_entry | `idx_user_id` | 普通 |
| word_progress | `uk_word_progress_user_word` / `idx_word_progress_user_review` / `idx_word_progress_user_book_learned` | 唯一 + 普通 ×2 |

**与 `database-guidelines.md` 的偏差说明**：规范建议 `idx_<表名>_<列名>` 带表名前缀，
但**交付文档的逐字一致性优先**（S3 是课设正式交付物，评审会对照文档核查脚本）。
S3 §7.1 的 `uk_username` / `idx_category` / `idx_book_id` / `idx_user_date` / `idx_user_book` /
`idx_user_id` 共 8 个索引名按原样采用；`user_word_book` 的两个原样索引名恰好
不带表名前缀（`idx_user_id` / `idx_book_id`），与 S3 一致。

**注意**：加固新增的唯一索引（S3 未定义）仍带表名前缀以避免歧义，
即 `uk_user_word_book_user_book` / `uk_study_record_user_word_date_type`；
`word_progress` 为新增表，索引名自然带表名前缀。

> 索引名影响应用层零处（MyBatis-Plus 不引用索引名），因此改名零风险。
> 已实测 14/14 索引（名称 + 列组合 + 唯一性）与预期完全一致。

### 3.5 时间戳填充：ORM 单套机制（G2 复核后决策）

**最终决策**：`create_time` / `update_time` **不带任何数据库默认值**
（既无 `DEFAULT CURRENT_TIMESTAMP`，也无 `ON UPDATE CURRENT_TIMESTAMP`），
两列均为 `DATETIME NOT NULL`，值**全部由 MyBatis-Plus `MetaObjectHandler` 填充**
（阶段 2 已在 `MybatisPlusConfig#metaObjectHandler` 实现 `insertFill`/`updateFill`）。

**理由**：避免 ORM 与数据库两套机制各写一次时间戳导致不一致
（典型症状：`UPDATE` 走 XML 或裸 SQL 时 `update_time` 由 DB 改、`create_time` 由 ORM 改，语义分叉）。
单一来源更易推理，也与「实体 `@TableField(fill=...)`」的声明式配置对齐。

**约束传导（阶段 4 必须遵守）**：
- 7 个实体的 `createTime` / `updateTime` 字段**必须**标注
  `@TableField(value = "create_time", fill = FieldFill.INSERT)` 与
  `@TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)`，
  否则插入时该列无值 → `Field 'create_time' doesn't have a default value`（已实测该报错）。
- 任何**绕过 MyBatis-Plus** 的写库（`StatsMapper.xml` 只读，无此问题；
  但若未来出现 XML 写操作）必须手工赋值时间列。

> 实测反证：不带 `create_time` 的裸 `INSERT` 被拒（`1364 Field 'create_time' doesn't have a default value`），
> 证明 DB 层确实不再兜底；`user_word_book` 中间表（物理删除、无 `deleted`）同样适用。

---

## 4. 关键算法

### 4.1 每日学习计划生成

```
输入：userId, 今天
1. 取用户已选词书列表（user_word_book）
2. 为空 → 返回 {date, total:0, finished:0, checkedIn:false, words:[]}
3. 对每本词书：
   a. 复习词 = word_progress WHERE user_id=? AND book_id=? AND learned=1
              AND next_review_date <= CURDATE() AND deleted=0
              → 关联 word 取文本，isReview=true
   b. 新词 = word WHERE book_id=? AND deleted=0
            AND id NOT IN (SELECT word_id FROM word_progress WHERE user_id=? AND learned=1)
            ORDER BY id ASC LIMIT 20
            → isReview=false
   c. 词书内排序：复习词在前，新词在后
4. 跨词书拼接
5. finished = 该用户今日已上报 learn 的词数（word_progress.learned=1 且更新日期=今天，
   或 study_record 中 study_date=CURDATE() 的 STUDY 类型记录数）
6. checkedIn = 今日 check_in_date 是否存在
```

**多词书时 total 的语义**：契约示例是单本词书 `total:20`。本设计取「所有已选词书的复习词 + 每本 20 新词」之和，即 `total = Σ(review_i + new_i)`。`DAILY_NEW_WORDS=20` 是**每本词书**的额度（需求清单 §2.2 原话「每本已选词书每日 20 个新词」）。

**`learned=true` 的词仍返回**：契约 §3.1 明确「`learned=true` 的词前端跳过不展示」，故后端返回全量计划（含已学），由前端跳过。这样做的好处是**中途退出再进入能保留上下文**（进度条仍显示 n/total）。

### 4.2 打卡与连续天数

```
POST /checkin
1. 取今日计划 → 若 total=0 → 3002；若 finished < total → 3003
2. 查今日是否已打卡（study_record WHERE user_id=? AND check_in_date=CURDATE() AND deleted=0）
   存在 → 3001（幂等，不重复写）
3. 计算 streak：
   lastCheckInDate = (SELECT MAX(check_in_date) FROM study_record
                      WHERE user_id=? AND check_in_date < CURDATE() AND deleted=0)
   若 lastCheckInDate = CURDATE()-1 天 → streak = lastStreak + 1
   否则 → streak = 1
4. 写入：为今日已学的每个 (word) 写一条 study_record：
   study_type=STUDY, study_date=CURDATE(), check_in_date=CURDATE(), streak_days=streak
5. 返回 {checkInDate, streakDays, todayLearned, todayMistakes}
```

**幂等保证的双保险**：应用层查重（返回 3001）+ `study_record` 上加唯一索引
`uk_study_record_user_word_date_type (user_id, word_id, study_date, study_type)`，
防止并发重复打卡产生重复行。

> 注：该唯一索引不在 S3 原文中，属于**加固措施**，需在 S3 修订时补充说明。

### 4.3 间隔复习调度

```
常量 REVIEW_INTERVALS = {1, 3, 7, 15}  // 索引 0..3 对应 stage 1..4

复习上报 correct=true：
  newStage = min(stage + 1, 4)
  nextReviewDate = CURDATE() + REVIEW_INTERVALS[newStage - 1]
  // 注意：stage 直接决定间隔 —— stage=1→+1天, 2→+3天, 3→+7天, 4→+15天

复习上报 correct=false：
  newStage = 1
  nextReviewDate = CURDATE() + 1
  wrong_count += 1
  upsert mistake_entry(mastered=0, source='REVIEW')

学习上报（STUDY）的首次调度：
  首次学习（不认识）→ 立即收录错题本，并插入 word_progress(stage=1, next_review_date=CURDATE()+1)
  首次学习（认识）  → 插入 word_progress(stage=1, next_review_date=CURDATE()+1)
  ※ 即：只要学过就进入复习队列，从 stage 1 开始。
```

**「进入下一阶段」的语义澄清**：契约 §4.2 写「correct=true → stage+1」。结合需求清单 §3.2「答对进入下一阶段」，本设计解释为：**答对后 stage 递增，下次复习间隔按新 stage 计算**。例如 stage=1（今天到期）答对 → stage=2 → 下次 +3 天。这是唯一自洽的读法。

### 4.4 错题收录去重

```
收录(wordId, source)：
  existing = SELECT * FROM mistake_entry WHERE user_id=? AND word_id=? AND deleted=0
  if existing:
      UPDATE wrong_count = wrong_count + 1, source = <新来源>, mastered = 0
  else:
      INSERT (user_id, word_id, source, wrong_count=1, mastered=0)
```

**`mastered` 的处理**：已在错题本中且 `mastered=1` 的词再次答错 → **重置为 0**（重新变为未掌握）。这是合理推断，需在实现中注释说明。

### 4.5 统计聚合（SQL 内完成）

> **日期列的选用（勿「顺手统一」）**：两个统计刻意用不同日期列——
> 热力图按 `check_in_date`（S3 §7.5「打卡热力图按 `check_in_date` 聚合」、
> 需求清单 §5.1、接口详细设计 §7.1 三处一致）；
> 掌握度按 `study_date`（S3 §5.6）。
> 语义差别：**复习不打卡**，但属于学习活动，故只在掌握度中体现。
>
> **时间边界由 Java 传入 `since` 参数**，不用 MySQL 专有的
> `DATE_SUB(CURDATE(), INTERVAL n DAY)`：保证 H2（MySQL 模式）与真实 MySQL
> 执行同一份 SQL，避免「测试通过、生产报错」的方言偏差，
> 同时把「回溯多少天」这条业务规则显式留在 Service 层便于单测注入固定日期。

热力图（近 12 周 = 84 天，对应接口 `?weeks=12`）：
```sql
SELECT check_in_date AS date, COUNT(DISTINCT word_id) AS wordCount
FROM study_record
WHERE user_id = ? AND check_in_date IS NOT NULL
  AND check_in_date >= ?   -- since = today - weeks*7，由 Java 计算
  AND deleted = 0
GROUP BY check_in_date
```
`level` 在 Java 侧按 `0/1–10/11–20/21+` 分档（简单映射，不需要 SQL）。
补齐缺失日期为 0（前端日历格需要连续日期序列，共 84 天 = 12 周）。

掌握度曲线（近 30 天）：
```sql
SELECT study_date AS date,
       SUM(CASE WHEN study_type='STUDY'  THEN 1 ELSE 0 END) AS learnedCount,
       SUM(CASE WHEN study_type='REVIEW' THEN 1 ELSE 0 END) AS reviewCount
FROM study_record
WHERE user_id=? AND study_date >= ? AND deleted=0   -- since 由 Java 计算
GROUP BY study_date
```
`mistakeCount` 与 `masteryRate` 单独查（错题表按 `create_time` 的日期聚合 +
`countMasteredMistakes/countTotalMistakes` 计算当前掌握比例）。

---

## 5. 鉴权设计

### 5.1 JWT

- 载荷：`userId`(Long)、`role`(String)，`exp` = 签发 + 7 天（604800000ms）。
- 算法 HS256，密钥从环境变量 `JWT_SECRET` 读取。
- **`JWT_SECRET` 无默认值（fail-fast）**：`application.yml` 写作 `secret: ${JWT_SECRET}`，缺失即启动失败（`Could not resolve placeholder 'JWT_SECRET'`）。
  - 为什么反转「给个默认值兜底」的常见做法：红线要求「无明文密钥入库」。若给默认值，一个忘了配环境变量的生产部署会**静默**地用公开在仓库里的密钥签发 token——任何人都能伪造 JWT。启动失败是这里唯一安全的行为。
  - 本地开发由 gitignore 的 `application-local.yml` 提供（profile=local），仓库内只留 `application-local.yml.example` 模板。
- 库选型：`jjwt 0.12.x`（`jjwt-api` / `jjwt-impl` / `jjwt-jackson`）。

### 5.2 拦截器

```java
JwtInterceptor:
  放行：OPTIONS 预检；/api/client/auth/register, /api/client/auth/login, /api/client/auth/wx-login
  其余：解析 Authorization: Bearer <token>
        - 头缺失/格式错/过期/签名错 → 401 + 1006
        - 查库校验 user.status 三态：
            DISABLED(0)  → 401 + 1003（禁用即时生效）
            UNKNOWN(无)  → 401 + 1006（账号已删/注销，引导重登）
            ACTIVE(1)    → 继续
        - 路径以 /api/admin/ 开头且 role != ADMIN → 403 + 1007
        - 通过 → 写入 ThreadLocal 的 UserContext
  afterCompletion：**必须** UserContext.clear()（Tomcat 复用工作线程，不清理会串号）
```

**禁用即时生效**的实现关键是**每次请求都查库**校验 status（而非信任 token 内容）。这是契约的显式要求（§8.7「禁用后该用户 JWT 下次校验返回 401」）。为避免每请求一次查询的开销，可加**短 TTL 本地缓存（如 10s）**；但为演示确定性，**一期直接查库**（用户表小、QPS 低），在代码中注释说明取舍。

**为何是「三态」而非「布尔」**（阶段 5 决策）：原设计只有 `isActive(Long) → boolean`，`false` 一个值要同时承载两种语义，但二者用户可见行为不同——被禁用的用户应「联系管理员」（1003），已被删除/注销账号的持有者应「重新登录」（1006）。混用会让后者去找到一个根本不存在的账号。

### 5.3 BCrypt

`spring-security-crypto` 的 `BCryptPasswordEncoder`（**只引 crypto 模块，不引整个 spring-security**，避免默认安全链干扰自定义拦截器）。强度 10（Spring Security 默认，约 50–100ms/次）。

### 5.4 CORS

管理端 Vite dev server（默认 5173）需跨域。`WebMvcConfig#addCorsMappings` 允许 `http://localhost:5173` / `127.0.0.1:5173`（**参数化为 `cors.allowed-origins` / 环境变量 `CORS_ALLOWED_ORIGINS`**，不硬编码），允许 `Authorization` 头。小程序端无 CORS 限制（走微信请求）。

### 5.5 第三方调用：微信 code2Session（阶段 5）

- 专用 `wxRestTemplate`（连接 5s / 读 5s）：该调用位于**登录主链路上**，用户在前端等待，不能用默认的 15s 读超时。
- 凭据 `wx.appid` / `wx.secret` 从 `WX_APPID` / `WX_SECRET` 读取；未配置时不发 HTTP，直接 1004（保证无 AppID 时服务仍可启动、账号密码登录不受影响）。
- URL 参数化为 `wx.code2session-url`（`WX_CODE2SESSION_URL`）。
- 所有失败路径（未配置 / 网络异常 / `errcode != 0` / 响应缺 openid / 非 JSON）统一映射为 **1004**，**绝不**冒泡成 9001 系统错误。
- **日志红线**：绝不打印请求 URI（query 里拼了 secret），只记异常类名与微信返回的 `errcode`/`errmsg`。

### 5.6 账号唯一性与并发（阶段 5）

「先查后插」有竞态窗口，**正确性由数据库唯一索引保证**，应用层检查只为友好提示：

| 场景 | 索引 | 冲突后处理 |
|---|---|---|
| 注册重名 | `uk_username` | → 1001（与预检查同一语义） |
| 微信并发建号 | `uk_openid` | 重查复用已建账号；重查仍无则**原样抛出**交全局处理器 |

微信账号的 `username` 与 `password_hash` 均置 NULL：MySQL 唯一索引允许多个 NULL，既不影响 `uk_username`，也从数据层保证其无法走密码登录。

---

## 6. AI 调用与降级设计

### 6.1 客户端

`RestTemplate` + `SimpleClientHttpRequestFactory`，**按接口设置不同超时**。因为三个接口超时不同（15/30/60s），设计为：

```java
// AiService 中按功能使用不同的 RestTemplate 或每次设置 factory 超时
```

更清晰的做法：注入一个 `RestTemplateBuilder`，每次调用 `.setConnectTimeout(...).setReadTimeout(...)` 构造专用实例（Spring Boot 3 提供 `RestTemplateBuilder`）。

### 6.2 降级矩阵

| 接口 | 成功 | 超时(5001) | 异常(5002) | 特殊前置 |
|---|---|---|---|---|
| `ai/example` | 返回 AI 例句，`degraded:false` | **HTTP 200, code:"0", degraded:true**，example=`word.example`，mnemonic="" | 同超时 | — |
| `ai/explain` | 返回 explanation+advice，`degraded:false` | 抛 BizException(5001) | 抛 BizException(5002) | — |
| `ai/report` | 返回三分段报告，`degraded:false` | 抛 5001 | 抛 5002 | 学习天数 < 3 → 5003，且**不发起 HTTP 调用** |

**关键实现细节**：`ai/example` 的降级**不能抛异常**——它必须在正常返回路径上返回 `code:"0"` 且 `degraded:true`。这是前端契约（前端按 `degraded` 显示黄条，不当作错误）。这点必须在代码中显式注释，否则极易被写成抛异常。

### 6.3 监控埋点

```java
@Component AiMetrics {
    // 内存计数器（AtomicLong），按功能分组
    record CallStat { AtomicLong total, success, degraded, timeout, error; AtomicLong totalCostMs; }
    Map<String, CallStat> stats;   // key: example/explain/report
    Deque<DegradeEvent> recentEvents;  // 容量 100，环形
}
```
- `DegradeEvent { time, feature, costMs, reason, result }`
- `GET /api/admin/monitor/ai-stats` 读取它 + 补充「注册用户数」等系统概览。
- 重启后清零。**一期明确不持久化**（需求清单 §6.3「一期可用简单内存计数器实现」）。
- 同时以 WARN 记日志，作为可持久观测信号。

---

## 7. 词库导入设计

> **本节的规则已在阶段 6 落地并被测试锁定**；下面各条同时标注了「实际实现位置」与
> 「为什么这样做」。凡实现与本节早期草稿不一致之处，均以本节为准（已回改）。

### 7.1 解析（单一实现，双入口复用）

`WordImportService.parseLine(String jsonLine, Long bookId)` → `Word 或 null（跳过行）`

```
解析 JSON（Jackson readTree；解析失败 → null）
word      = node.word        （缺失/全空白 → null，唯一必填字段）
phonetic  = node.uk          （缺失 → ""）
translation: node.translations[] 逐项 "{type}. {translation}"，用 "；" 连接
             （type 缺失时仅保留 translation，避免孤立的 ". "）
example   = node.sentences[0].sentence（缺失/空数组 → ""）
definition / audioUrl 一期留空（源数据无英文释义；需求清单 §7.4 不做发音）
防御性列宽校验：word>100 / phonetic>100 / translation>255 / example>500 → null
```

实现位置：`service/impl/WordImportServiceImpl#parseLine`（**全项目唯一映射实现**）。
测试：`service/WordImportServiceImplTest`（11 例，含真实源数据样例行）。

**关键取舍：缺字段填空串而非 null。** 三个字段在库里都是 `NULL DEFAULT NULL`，
但若把 null 传到 DTO，前端卡片就要处处判空（"phonetic 有值才显示音标"）。
统一填空串后，前端逻辑退化为「非空才渲染」，且不会出现 JSON 里 `null` 与 `""` 两种空态。

**同词多义项（重要）**：源文件由多份数据合并，**含重复单词行且释义各异**
（实测 CET4 7508 行仅 4544 个不同单词，如 `absorb` 出现 3 次）。
需求要求 `word_count` 严格等于**文件行数**，故**逐行入库、不做单词去重**。
重复词各自独立成为不同的 `word_id`，`word_progress` 的唯一键是
`(user_id, word_id)`，因此学习进度天然相互独立，不会互相覆盖。

### 7.2 两个入口

| 入口 | 实现 | 用途 |
|---|---|---|
| `POST /api/admin/books/import`（multipart） | `WordImportService#importFromStream(InputStream, name, category)` | 管理端上传，返回 `{bookId, success, skipped}` |
| `VocabSeedRunner`（`CommandLineRunner`，`@ConditionalOnProperty(vocab.seed=true)`） | `WordImportService#importLines(Stream<String>, bookId)` | 一次性种子导入，从 `data/vocab-source/*.jsonl` 读 |

两者**必须调用同一个 `parseLine`**——`importFromStream` 与 `importLines`
都汇聚到它，不存在第二份字段映射代码。

**为什么用 `@ConditionalOnProperty` 而不是 `@Profile`**：种子导入是「一次性运维动作」，
不是一种运行环境。用 `--vocab.seed=true` 参数控制更贴合语义，也避免
「seed profile 与 local profile 不能同时激活」这类无意义的组合限制。

**种子导入的幂等与门禁**（`VocabSeedRunner`）：
- 同名（name+category）词书已存在则**跳过**——误跑两次不会得到 6 本词书。
- 每本导入后**断言行数 == 期望值（7508/5651/9602）**，不等则抛异常并让进程以非零码退出。
  这正是 G3 闸口的自动化形态：词库是本项目的数据基石，静默少几行会让
  「7508 词的词书」变成不可信的中间态。
- 单本失败不阻断其余两本，最后统一汇总失败项再抛异常（避免「日志有 ERROR、退出码却是 0」）。

### 7.3 批量写入

**`saveBatch` 的语义澄清（修正早期草稿）**：MyBatis-Plus 的
`Db.saveBatch(list, 1000)` / `ServiceImpl.saveBatch` 内部是**循环调用单条 `insert`**
（3.5.9 字节码已核实：`saveBatch -> saveBatch(c, 1000) -> 逐条 insert`）。
对 22761 行的词库这意味着 22761 次数据库往返。

因此本项目改写为**多行 VALUES 单语句批量插入**：
`mapper/WordMapper#insertBatch(List<Word>)` + `resources/mapper/WordMapper.xml`，
每批 1000 行 → 一条 `INSERT INTO word (...) VALUES (...),(...),...`。
这才是 database-guidelines.md「禁止循环单条插入」的实质要求。

**为什么这个 XML 必须显式写 `NOW()`**：时间列自阶段 3 起为裸 `DATETIME NOT NULL`
（G2 决策：填充唯一来源是 `MetaObjectHandler`）。手写 XML 绕过了
`MetaObjectHandler`，所以 `create_time` / `update_time` 必须由 SQL 显式填充；
`deleted` 同理显式置 0（`@TableLogic` 的默认值只在 MP 自己拼 SQL 时才附加）。
这是 design.md §3.5 那条约束的**唯一兑现点**，也是最容易漏的地方。

**词书创建时机（阶段 6 实测修正）**：`importFromStream` **延迟到第一条合法行
才创建词书**。早期实现在解析前就 `insert` 词书，结果是「整份文件全是坏行」
抛 2004 之后库里仍留下一本 0 词的空壳（本方法的 `@Transactional` 只在
调用方也是事务时才回滚）。现在把「是否存在合法行」与「创建词书」合并为一步，
坏文件不产生任何副作用。已被 `BookControllerIntegrationTest#importAllBadLinesReturns2004` 锁定。

**`word_count` 校正**：导入完成后 `COUNT(*)` 回写，而非自增累加
（累加一旦中途失败就漂移）。`ImportResult.success` 也取同一 COUNT，
保证「响应里的 success」与「library 里的 word_count」同源。

### 7.4 错误码边界（查询参数 vs 文件内容）

| 情形 | 响应 | 理由 |
|---|---|---|
| 单行格式错误 | `skipped++`，HTTP 200 `code=0` | 契约 §8.1「格式错误行跳过并计数」 |
| 整个文件无一行合法 | `code=2004`，**不留空词书** | 契约 §8.1「整体格式非法时」 |
| `category` 不在白名单 | `code=1005` | 参数错误，不是文件格式错误；两者用户动作不同 |
| `file` 流不可读 | `code=2004` | 与整体非法同属「这份文件用不了」 |

### 7.5 列表页的 N+1 规避

`GET /api/client/books` 每个元素要带 `learnedCount`（该用户在此词书的已学词数）。
若逐本调用 `countLearnedWordsInBook`，N 本词书就是 N 次 COUNT。
故新增 `StatsMapper#selectLearnedCountByBook`——一次 `GROUP BY book_id`
拿到全部，Service 侧转 `bookId → count` Map 后填充，SQL 次数恒为 1。

`selected` 同理：一次 `selectList(user_word_book where user_id=?)` 转 `Set<Long>`，
而不是每本书查一次关联表。

> 别名注意：该查询的 `AS book_id` / `AS learned_count` 沿用下划线风格。
> MyBatis 的列名自动映射对大小写不敏感，且 `map-underscore-to-camel-case`
> 负责映射到实体的 `bookId`/`learnedCount`；用驼峰别名在 H2
> （`DATABASE_TO_LOWER=TRUE`）下会被折成小写，反而映射不上。

---

## 8. 权衡与备选方案

| 决策 | 选择 | 备选 | 权衡理由 |
|---|---|---|---|
| 复习进度存储 | 新增 `word_progress` 表 | 从 `study_record` 重放计算 | 性能与可读性压倒"表数"的形式约束；已在文档显式记录偏离 |
| 禁用即时生效 | 每请求查库 | 缓存/黑名单 | 一期 QPS 低，正确性优先；演示需要确定性 |
| 分页实现 | MyBatis-Plus `Page` | 手写 LIMIT | 规范硬要求 |
| 统计聚合 | SQL GROUP BY | 内存计算 | 规范硬要求 |
| AI 超时 | `RestTemplateBuilder` 每调用构造 | 全局单个超时 | 三接口超时不同（15/30/60），必须分别设置 |
| 唯一索引加固 | `study_record` 加 `uk_user_word_date_type` | 仅应用层查重 | 并发安全 + 演示可重复性 |
| 词库数据 | 本地落盘 + 双入口导入 | 运行时远程拉取 | jsdelivr 不可达；GitHub 大文件不稳 |
| 词库去重 | **逐行导入、不去重** | 按 word 去重后入库 | 需求要求 `word_count` = 文件行数（7508/5651/9602）；源数据本身含重复词且释义各异，去重会破坏文档中的计数承诺 |
| 批量插入 | **多行 VALUES 单语句**（自写 XML） | `saveBatch`/`Db.saveBatch` | 后者实为循环单条 insert；22761 行会变成 22761 次往返。见 §7.3 |
| 词书创建时机 | 首个合法行处创建 | 解析前创建 | 避免「全坏行」输入留下 0 词空壳（阶段 6 实测缺陷） |
| 种子导入开关 | `@ConditionalOnProperty(vocab.seed)` | `@Profile("seed")` | 一次性运维动作≠环境；避免 profile 组合限制 |
| 列表 learnedCount | `GROUP BY book_id` 一次取全 | 逐本 COUNT | 规避 N+1。见 §7.5 |

---

## 9. 兼容性与迁移

- **无历史数据**：本项目从零开始，无需数据迁移。
- **脚本可重复执行**：`CREATE TABLE IF NOT EXISTS`，方便课设演示反复执行。
- **库名固定** `ai_vocab`，与现有 `anime_tracker` 完全隔离。
- **配置分层**：`application.yml`（提交，占位符）+ `application-local.yml`（gitignore，真实凭据）。

---

## 10. 回滚策略

- 所有改动在独立分支 `feat/backend-api`（由 `task.py` 记录）。
- 数据库为新建库，最坏情况 `DROP DATABASE ai_vocab` 重建（脚本可重跑）。
- **不触碰 `anime_tracker` 库**（红线）。
- 若 `word_progress` 设计在实现中被证明有缺陷，回滚即在 `V002` 基础上加 `V003` 调整，不修改已执行的脚本。
