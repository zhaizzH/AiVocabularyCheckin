# Journal - zhaizzH (Part 1)

> AI development session journal
> Started: 2026-10-16

---

## 2026-10-16 — Bootstrap Guidelines 完成

- 依据 `docs/` 下需求规格说明书确定技术栈：`backend/`（Spring Boot 3 + MyBatis-Plus + MySQL 8）、`ai-service/`（FastAPI + LangChain，无状态、不连库）、`miniprogram/`（微信原生）、`frontend/`（React 18 + TS + Vite + AntD，由 web/ 改名）。
- 填写 11 个 spec 文件（backend 5 + frontend 6），全部中文。核心约定：Result 包装 + 错误码分段、AI 降级一等路径、services 层统一请求、LambdaQueryWrapper 优先、密钥仅环境变量。
- 状态管理按团队决定保持灵活，不指定库。
- `guides/` 三个思考指南已翻译为中文；删除 Trellis 项目专属章节（模板注册、版本文档、模式探测等与本项目无关），保留通用原则并新增本项目边界映射。
- 任务已归档至 `.trellis/tasks/archive/2026-10/00-bootstrap-guidelines`（auto-commit 未生效：仓库尚无提交记录）。

## 2026-10-16 — S1-S3 文档 V1.1 修订

- 任务 `10-16-docs-v2-revision`（轻量，PRD-only）。
- S1：补管理端 React 技术栈与 JWT/响应包装决策、新增双前端差异风险(5)、文末新增版本表 V1.0/V1.1。
- S2：§4.1/4.2 管理端改为 React18+TS+Vite+AntD5、补 MyBatis-Plus/JWT/BCrypt；§4.3 补 Result 包装与错误码分段；§4.4 补 React 可行性；§5.3 指认 S3 §4 接口表；§5.4 补降级提示文案与接口一致性需求；§8 补鉴权约束。
- S3：版本表追加 V1.1；§8.2 注明管理端技术栈已确定。
- 全文统一术语“网页管理端”，消除“HTML/CSS/JavaScript”与“网页端”旧表述。



## Session 1: backend 阶段 2：工程骨架（Spring Boot 3.5 + MyBatis-Plus）
<!-- trellis-session: v=2 fp=0809ec98d4f0f187 -->

**Date**: 2026-10-16
**Task**: backend 阶段 2：工程骨架（Spring Boot 3.5 + MyBatis-Plus）
**Branch**: `main`

### Summary

完成 backend 阶段 1（建库+词库落盘）与阶段 2（工程骨架）。37 个单测全绿，构建与启动实测通过，并修掉兜底异常处理器吞 404 的隐患。

### Main Changes

- 阶段 1：data/vocab-source/ 落盘 CET4(7508)/CET6(5651)/KAOYAN(9602)+LICENSE；scripts/fetch-vocab.sh 带行数校验
- 阶段 2：pom.xml（Spring Boot 3.5.6/Java 17/MyBatis-Plus 3.5.9+jsqlparser/jjwt 0.12.6/security-crypto/h2）
- common/ 六个类：Result（成功码 "0"）、ErrorCode（1xxx-9xxx 分段）、BizException、PageResult、Constants、GlobalExceptionHandler
- config/ 七个类：MybatisPlusConfig、JwtUtil、JwtInterceptor、UserContext、UserStatusValidator、WebMvcConfig、RestTemplateConfig
- 安全加固：JWT_SECRET 移除默认值改为 fail-fast；CORS 白名单参数化；local 配置走 .example 模板
- 修隐患：兜底异常处理器曾把 404 吞成 200+9001，现 404/405/415 保留真实 HTTP 语义
- 契约增补（待 G6 回写）：补 1006 TOKEN_INVALID / 1007 FORBIDDEN / 9002-9004，见 research/01-contract-gaps-stage2.md

### Git Commits

| Hash | Message |
|------|---------|
| `140e601` | feat(backend): 阶段 2 工程骨架 — Spring Boot 3.5 + MyBatis-Plus 基础框架 |

### Testing

- [OK] mvn clean package 通过；mvn -o 离线构建通过；产物 30.6MB jar
- [OK] 37 个单测全绿：ResultAndErrorCodeTest(11)/JwtUtilTest(7)/JwtInterceptorIntegrationTest(11)/GlobalExceptionHandlerIntegrationTest(8)
- [OK] 实测：无 token→HTTP 401+1006；缺失 JWT_SECRET→启动失败(fail-fast)；仅给 JWT_SECRET→正常启动；CORS 预检→200
- [OK] 规范自查：无 System.out.println、无空 catch、无硬编码密钥/URL、日志无敏感值、依赖树确认无 spring-boot-starter-security

### Status

[OK] **Completed**

### Next Steps

- 阶段 3：数据库迁移脚本 V001__init.sql（6 表）+ V002__word_progress.sql，验证可重复执行 → 闸口 G2
- 阶段 4-11：实体+Mapper → 认证（阶段5接入 UserStatusValidator 真实实现）→ 词库+种子导入（G3）→ 学习/复习/错题（G4）→ AI 降级（G5）→ 统计 → 管理（先回写契约 G6）→ 收尾（G7）

---

## 2026-10-16 — backend-api 阶段 4：实体与 Mapper

### Goal

7 张表建 MyBatis-Plus 实体 + Mapper + 统计聚合 XML，过闸口（MyBatis-Plus 能读写 7 张表）。

### Done

- entity/ 7 个：User / WordBook / Word / UserWordBook / StudyRecord / MistakeEntry / WordProgress
  - 时间列统一标 @TableField(fill=INSERT / INSERT_UPDATE)（G2 去 DB 默认值后的必填约束）
  - 6 表 @TableLogic deleted；user_word_book 物理删除、无 deleted（全项目唯一）
- mapper/ 7 个 BaseMapper + StatsMapper；resources/mapper/StatsMapper.xml（7 个查询，逐个手写 deleted = 0）
- config/JacksonConfig：JSR-310 全局格式 yyyy-MM-dd HH:mm:ss / yyyy-MM-dd
  （spring.jackson.date-format 对 JSR-310 无效，漏配会让 createTime 输出带 T 的 ISO 串）
- dto/response/HeatmapDay、MasteryPoint
- 测试 EntityMappingIntegrationTest（H2，19）+ RealMysqlMappingIT（真实 MySQL，默认跳过）
- scripts/verify-schema-entities.py（实体↔真实库双向核对）、verify-h2-schema-parity.py（H2↔V001/V002 漂移检测）

### 修复的真实缺陷

1. JDBC URL characterEncoding=utf8mb4 → Connector/J 用 Charset.forName 解析，utf8mb4 非法 →
   连接直接失败（Unsupported character encoding 'utf8mb4'）。阶段 2 起潜伏，因从未真正连库而未暴露。
   三处 URL 改 UTF-8。
2. StatsMapper.xml 用 MySQL 专有 DATE_SUB(CURDATE(),...) → H2 报 Function "date_sub" not found。
   改为起始日期由 Java 计算后作 since 参数传入：H2 与真实 MySQL 执行同一份 SQL，
   且「回溯多少天」显式落在 Service 层。
3. JwtUtilTest.tamperedTokenReturnsNull 是 flaky 测试：原断言改签名段末字符，
   而 32 字节 HMAC 的 base64url 末字符低 2 位是填充位、解码时丢弃 → 实测 6.0% 概率
   改写后签名字节完全相同。改为篡改 payload 首字符；连跑 12 次全绿。

### 设计修正

- 热力图按 check_in_date 聚合（S3 §7.5 + 需求清单 §5.1 + 接口设计 §7.1 三处一致）；
  design.md §4.5 草稿误写 study_date，已改并补注「复习不打卡但属学习活动」（掌握度用 study_date）。
- 掌握度 mistakeCount 改 COALESCE(MAX(...))，GROUP BY 只按 study_date，消除按关联聚合值分组的脆弱写法。
- 删除冗余 .gitkeep（目录已有真实内容且会打入 jar）；UserWordBookMapper 注释纠正（物理删除无 @TableLogic）。

### 安全

- 校验脚本初版把 DB 密码写成默认值（违反禁硬编码凭证红线），改为无默认值 + 缺密码 fail-fast。
- 该密码曾随研究文档误入提交 23720a3：因无 git remote，已 reset --soft 折叠重提 + reflog expire + gc prune，
  全历史 git grep 三个敏感串均 0 命中，污染对象已不可访问。

### Git Commits

| Hash | Message |
|------|---------|
| `d8581ac` | feat(backend): 阶段 4 实体与 Mapper — 7 表映射 + 统计聚合 |

### Testing

- [OK] mvn -o clean test：56 全绿（阶段 2 的 37 + 本阶段 19），连跑 3 次稳定
- [OK] RealMysqlMappingIT（-Dmysql.it=true）：真实 MySQL 8.4.9 + ONLY_FULL_GROUP_BY 上 7 表
  插入/读回/聚合/update 刷新 updateTime 全通过；事务回滚，7 表精确计数 = 0
- [OK] 掌握度断言经变异测试证明有效（错题关联表改错 → 测试立刻失败）
- [OK] 校验脚本：实体 7 / 67 列双向一致；schema-h2.sql 与 V001/V002 无漂移
- [OK] 红线：anime_tracker 20 表未动；无 System.out / 空 catch / 硬编码密钥

### Status

[OK] **Completed**（阶段 4；本阶段无独立闸口，下一闸口为 G3）

### Next Steps

- 阶段 5 认证模块：AuthService 落地 UserStatusValidator 真实实现（查 user.status，
  使「管理员禁用 → 该用户 JWT 立即失效 1003」生效）+ 真实 AppID/AppSecret 走 code2Session
- 阶段 6 词库与种子导入 → 闸口 G3（三本词书计数 7508/5651/9602 严格一致）
- 阶段 7 学习/复习/错题 → 闸口 G4；阶段 8 AI 降级 → G5；阶段 10 前回写契约 → G6；阶段 11 收尾 → G7


## Session 2: backend 阶段 5：认证模块（5 个 auth 接口 + 禁用即时生效）
<!-- trellis-session: v=2 fp=1fc470090199925e -->

**Date**: 2026-10-16
**Task**: backend 阶段 5：认证模块（5 个 auth 接口 + 禁用即时生效）
**Branch**: `main`

### Summary

实现《接口详细设计》§1 全部 5 个认证接口，并落地 §8.7「管理员禁用后 token 立即失效」。UserStatusValidator 升为三态枚举以区分 1003 与 1006。118 测试全绿（+62），真实 MySQL + 真实微信 API 冒烟 38/38。

### Main Changes

- dto/request/ 4 个 + dto/response/ 4 个 + service/AuthService(+Impl) + controller/AuthController（5 接口）
- client/WxApiClient：真实 code2Session，专用 wxRestTemplate（5s 读超时）；未配置/S网络错/errcode!=0/缺 openid/非 JSON 统一 → 1004，绝不冒泡 9001
- UserStatusValidator 由单方法 isActive(Long) 升为三态枚举 ACTIVE/DISABLED/UNKNOWN；新增 UserStatusValidatorImpl 每请求主键查库（@TableLogic 自动附 deleted=0）
- JwtInterceptor：DISABLED→401+1003、UNKNOWN→401+1006；管理端非 ADMIN→403+1007
- 唯一性由 DB 唯一索引兜底（非仅先查后插）：并发注册→1001；并发微信建号→重查复用，重查仍无则原样上抛
- 微信账号 username/password_hash 均置 NULL：MySQL 唯一索引允许多 NULL，数据层杜绝其走密码登录
- design.md §5 补全：JWT_SECRET fail-fast（原文「必须有默认值兜底」与红线冲突，已修正）、三态校验、wx 客户端、唯一性并发；新增 §5.5/§5.6
- implement.md：阶段 5 三项勾选 + 实施记录；闸口表 G1/G2 标记 ✅；新增 research/04-stage5-auth-review.md

### Git Commits

| Hash | Message |
|------|---------|
| `8ca2e17` | feat(backend): 阶段 5 认证模块 — 5 个 auth 接口 + 禁用即时生效 |

### Testing

- [OK] mvn -o clean test：118 全绿（阶段2 的 37 + 阶段4 的 19 + 本阶段 62），连跑 3 次稳定
- [OK] 新增 WxApiClientTest(8，起真实本地 HTTP 桩服务，非 mock 方法)：覆盖 URL 拼接/errcode 分支/非 JSON/连接失败/缺凭证不发请求
- [OK] 新增 AuthServiceImplTest(21)：真实库 + 并发分支桩注入（DuplicateKey→重查复用 / 重查无→上抛）
- [OK] 新增 AuthControllerIntegrationTest(25)：MockMvc 真实 HTTP，5 接口外部形状 + §8.7 禁用即时失效
- [OK] 改造阶段 2 两个测试类：原伪造 userId=999 签 token，现会被真实查库判为 UNKNOWN→401，改为先建真实用户
- [OK] 真实 MySQL + 真实微信 API 闸口冒烟 /c/tmp/stage5-smoke.sh 38/38：无效 code 得 errcode=40029→1004（证明 appid/secret 被微信接受）
- [OK] §8.7：直接 UPDATE user SET status=0 后同一 token 立即 401+1003；同账号重新登录也 1003
- [OK] 响应体 UTF-8 逐字节校验：1002 文案字节 e794a8...e8afaf 与期望一致；createTime 输出 2026-10-16 18:11:07（空格分隔）
- [OK] 红线：anime_tracker 仍 20 表、ai_vocab 7 表；user 表回到 0 行；无非 BCrypt 哈希；main 下无 System.out/硬编码凭证；application-local.yml 未被 git 跟踪

### Status

[OK] **Completed**

### Next Steps

- 阶段 6 词库模块 + 种子导入（单一 parseLine 双入口、saveBatch 1000、导入后校正 word_count）→ 闸口 G3（7508/5651/9602 严格一致）
- 阶段 7 学习/复习/错题核心闭环 → G4；阶段 8 AI 降级 → G5；阶段 9 统计（StatsMapper 已就位，补 Service/Controller）
- 阶段 10 管理模块前必须 G6 回写契约：1006/1007/9002-9004 + JSR-310 格式注 + §8/§10 新接口 + S3 三条（表数 6→7、加固唯一索引、时间列 ORM 填充）


## Session 3: backend 阶段 3+4：迁移脚本与实体/Mapper（G2 复核通过）
<!-- trellis-session: v=2 fp=60523b6f771b8976 -->

**Date**: 2026-10-16
**Task**: backend 阶段 3+4：迁移脚本与实体/Mapper（G2 复核通过）
**Branch**: `main`

### Summary

落地 V001/V002 迁移脚本（7 表，DDL 逐字照 S3 §7），完成 7 个实体 + 8 个 Mapper + StatsMapper.xml 聚合；G2 复核通过，56 测试全绿。

### Main Changes

- 阶段 3：`db/V001__init.sql`（6 表）+ `db/V002__word_progress.sql`（第 7 表）；
  DDL **逐字照 S3 §7**，字段长度实测全落 S3 宽度内，索引名回退 S3 §7.1 原样。
- **G2 决策**：时间列 `create_time`/`update_time` **不带数据库默认值**，统一交
  MyBatis-Plus `MetaObjectHandler` 单套填充（避免「DB 默认值 + ORM 填充」两套时间源）。
- 阶段 4：`entity/` 7 个 + `mapper/` 7 个 BaseMapper + `StatsMapper` +
  `resources/mapper/StatsMapper.xml`（逐个查询手写 `deleted = 0`）；
  `config/JacksonConfig` 补 JSR-310 全局格式 `yyyy-MM-dd HH:mm:ss` / `yyyy-MM-dd`。
- 测试支撑：`test/resources/schema-h2.sql`（H2 2.3.232）、`test/resources/application-test.yml`。
- **修 3 个真缺陷**：① JDBC URL `characterEncoding=utf8mb4` 非法（Connector/J 经
  `Charset.forName` 解析）→ 三处改 `UTF-8`；② `StatsMapper.xml` 用 MySQL 专有
  `DATE_SUB(CURDATE(), ...)` → 起点日期改由 Java 计算作 `since` 参数传入，
  使 H2 测试与 MySQL 生产跑**同一份 SQL**；③ `JwtUtilTest#tamperedTokenReturnsNull`
  是 flaky 测试（改签名段末字符有 ~6% 概率解码后字节不变）→ 改为篡改 payload 首字符。
- **安全**：校验脚本初版把 DB 密码写成默认值（违反禁硬编码凭证红线）→ 改为无默认值 +
  缺密码 fail-fast；该密码曾误入提交 `23720a3`，因无 remote 已 reset --soft 折叠重提 +
  `reflog expire` + `gc prune`，全历史 `git grep` 三个敏感串 0 命中。


### Git Commits

| Hash | Message |
|------|---------|
| `b3713dc` | chore(db): 阶段 3 迁移脚本 V001/V002 — 7 张表 |
| `d2a1ece` | chore(db): G2 复核调整 — 索引名回退 S3 原样、时间列交由 ORM 填充 |
| `d8581ac` | feat(backend): 阶段 4 实体与 Mapper — 7 表映射 + 统计聚合 |

### Status

[OK] **Completed**

### Next Steps

- 阶段 5 认证模块（AuthService + UserStatusValidator 真实实现）


## Session 4: backend 阶段 6：词库模块 + 种子导入（G3 通过）
<!-- trellis-session: v=2 fp=5c16acf603b75baf -->

**Date**: 2026-10-16
**Task**: backend 阶段 6：词库模块 + 种子导入（G3 通过）
**Branch**: `main`

### Summary

词库四接口 + 种子导入落地：三本词书 22761 词入库，G3 闸口实测通过（7508/5651/9602 严格一致、二次运行幂等），143 测试全绿。

### Main Changes

- `WordImportService#parseLine(String, Long)` = **唯一映射实现**，种子导入与
  JSONL 上传双入口共用；映射规则 `word`→`word` / `uk`→`phonetic` /
  `translations` 拼「词性. 释义」→`translation` / `sentences[0]`→`example`。
- `WordBookService` 新增 3 个管理端方法占位（阶段 10 接 Controller）：
  `adminListBooks()` / `importBook(InputStream,name,category)` / `deleteBook(Long)`。
- `VocabSeedRunner`（`@ConditionalOnProperty(vocab.seed=true)`）从
  `data/vocab-source/` 读，按 `name+category` 幂等跳过，导入后断言行数 == 期望值。
- **批量写入不用 `saveBatch`**：实测 MyBatis-Plus 3.5.9 的 `Db.saveBatch(coll, 1000)`
  是**循环单条 insert**（字节码已核实），22761 行 = 22761 次往返。改为自写
  `WordMapper#insertBatch` + XML 多行 `VALUES` 单语句（每批 1000）；XML 绕过
  `MetaObjectHandler`，故显式 `NOW()` + `deleted=0`。详见 design.md §7.3。
- **修 3 个真缺陷（均由测试先失败暴露）**：① 原实现先建词书再解析，整份文件全坏行抛
  2004 后库里残留 0 词空壳词书 → 改为**惰性扫描到首条合法行才建词书**
  （`@Transactional` 只在调用方也开事务时回滚）；② `ImportResult.success` 与
  `word_count` 不同源 → 统一取 `correctWordCount()`；③ 测试夹具未设
  `word_book.word_count`（实现侧无缺陷）。
- 逐行导入**不做单词去重**（需求要求 `word_count` == 文件行数，源数据本身含重复词）。


### Git Commits

| Hash | Message |
|------|---------|
| `e140517` | feat(backend): 阶段 6 词库模块 + 种子导入（G3 通过） |

### Testing

- [OK] mvn -o clean test → 143 tests, 0 failures, 0 errors（+25）
- [OK] G3 闸口实测：word_book 3 行、word 22761 行、每本 COUNT(*) 与 word_count 全等、时间列 NULL=0
- [OK] 二次运行 --vocab.seed=true 幂等跳过，行数不变
- [OK] HTTP 冒烟 20 用例全过（真实 MySQL + curl）
- [OK] 红线 anime_tracker 仍 20 表未触碰

### Status

[OK] **Completed**

### Next Steps

- 阶段 7 学习/复习/错题核心闭环 → G4


## Session 5: backend 阶段 7：学习/复习/错题核心闭环（G4 通过）
<!-- trellis-session: v=2 fp=f40a5d7e821496c7 -->

**Date**: 2026-10-16
**Task**: backend 阶段 7：学习/复习/错题核心闭环（G4 通过）
**Branch**: `main`

### Summary

实现契约 §3/§4/§5 共 11 个客户端接口，落地间隔复习算法与打卡闭环；G4 闸口以真实 MySQL + 真实 HTTP 端到端验证，231 测试全绿。

### Main Changes

- **DTO**：`dto/response/` 11 个 + `dto/request/{StudyRecordRequest,ReviewRecordRequest}`。
- **算法纯函数**：`service/ReviewSchedule`（`nextStage`/`intervalDays`/`nextReviewDate`，
  `REVIEW_INTERVALS=[1,3,7,15]`）；`common/Pages` 分页归一化（`WordBookServiceImpl` 一并重构复用）。
- **可注入「今天」**：`config/ClockConfig`（`Clock.systemDefaultZone()` Bean）+
  `MybatisPlusConfig` 构造注入 `Clock` 供 `MetaObjectHandler` 使用；
  测试侧 `testsupport/FixedClock` + `TestClockConfig`（`@Primary` 覆盖生产 Bean）。
- **Service/Mapper**：`StudyService` / `ReviewService` / `MistakeService` +
  `WordProgressMapper.xml`（NEW，4 表 JOIN，手工 `deleted=0`）；列表两段式避开 N+1。
- **修 2 个真缺陷（均由测试先失败暴露）**：
  1. **「做完反而不能打卡」**：计划原按实时推导生成，词上报后逐词从计划消失，
     全部做完 `total=0` → `checkIn` 抛 3002。改为「今日任务集合」语义并追加第三段
     「今天已上报过的词」（`todayReportedWordTypes`），使 `total` 恒定、
     `finished` 单调递增。**已回写契约 §3.1（V1.1）**。
  2. **计划候选集后滑**：`selectUnlearnedNewWordIds` 判据 `update_time < dayStart`
     被复习刷新误伤 → 改为 `create_time < dayStart` 且 `learned=1`。
- ⚠ **夹具教训（非产品缺陷）**：`study_record.study_date` 与 `check_in_date` 在真实数据里
  **恒等**（打卡时同时写入），跨天夹具回拨日期必须**成对回拨**；只改其一会使
  `lastCheckInDate` 假失败（第一版 `g4-crossday.py` 即踩此坑）。


### Git Commits

| Hash | Message |
|------|---------|
| `748062d` | feat(backend): 阶段7 学习/复习/错题核心闭环（§3/§4/§5） |
| `76cf216` | test(backend): G4 闸口通过——真实 MySQL + 真实 HTTP 端到端验证 |

### Testing

- [OK] mvn -o test → 231 tests, 0 failures, 0 errors（+88）
- [OK] g4-smoke.py 26 PASS / 0 FAIL（真实 MySQL + 真实 HTTP）
- [OK] g4-crossday.py 18 PASS / 0 FAIL（自包含跨天：streak=2、复习词 overlap=20、stage 1→2 隔 3 天、3→4 隔 15 天）
- [OK] 数据完整性：learned=1 且 next_review_date IS NULL=0、stage 越界=0、三类唯一约束重复组=0

### Status

[OK] **Completed**

### Next Steps

- 阶段 8 AI 中转与降级（契约 §6）→ G5


## Session 6: backend 阶段 8：AI 中转与降级（G5 通过）
<!-- trellis-session: v=2 fp=14cf5ba4f65371c2 -->

**Date**: 2026-10-16
**Task**: backend 阶段 8：AI 中转与降级（G5 通过）
**Branch**: `main`

### Summary

实现契约 §6 三个 AI 接口与降级矩阵，ai-service 未启动时降级路径全绿；修掉 isTimeout 漏判生产超时异常类型的真缺陷，250 测试全绿。

### Main Changes

- `ai/AiMetrics`（内存埋点，design.md §6.3）：`AtomicLong` 计数 + `ConcurrentHashMap` 分特性
  + `ConcurrentLinkedDeque` 环形降级事件（容量 100）；**降级也计入 total**。
- `ai/AiServiceClient`：`callExample`(15s)/`callExplain`(30s)/`callReport`(60s)，
  每次 `.setConnectTimeout(min(5000,timeout)).setReadTimeout(timeout).build()` 构造专用实例，
  只抛 `AiCallException`（`isTimeout()` 区分 5001/5002），绝不暴露 `RestTemplate` 异常。
- `AiServiceImpl` 三用例**降级矩阵**：`example` 内部捕获异常 → HTTP 200 +
  `degraded:true` + 库内例句 + `mnemonic:""`（**显式注释「刻意吞异常，绝不抛 5001/5002」**）；
  `explain` 错题不存在或不属于该用户 → **统一 4001**（避免 id 枚举探测他人错题）；
  `report` 学习天数 < 3 → **5003 且不发起 HTTP**，`studyStats` 在 SQL 内聚合。
- **修 1 个真缺陷（测试先失败暴露）**：`AiServiceClient#isTimeout()` 原只判
  `java.net.SocketTimeoutException`。实测异常链为 `ResourceAccessException` →
  `java.net.http.HttpTimeoutException`（Spring Boot 3.5 默认 `JdkClientHttpRequestFactory`），
  与前者是**兄弟类（互不继承）** → **生产默认配置下所有真实超时都会被误判为 5002**。
  修复为同时判两类超时，Javadoc 写明「超时判定的唯一真相源」，并以
  `explainThrows5001OnRealTimeout` + `explainThrows5002WhenServiceDown` 锁死回归。
- **降级测试禁用 Mock 伪造故障**：改用三个真实对端——`127.0.0.1:1`（连接被拒 → 5002）、
  `testsupport/SilentHttpServer`（接受连接但永不响应 → 真实读超时 → 5001）、
  `testsupport/StubAiServer`（真实 HTTP 校验字段名）。Mock 复现不出真实异常类型。


### Git Commits

| Hash | Message |
|------|---------|
| `a097e6e` | feat(backend): 阶段 8 AI 中转与降级（契约 §6） |
| `45eb7d8` | test(backend): G5 闸口通过——ai-service 未启动时降级路径全绿 |

### Testing

- [OK] mvn -o test → 250 tests, 0 failures, 0 errors（+19）
- [OK] g5-smoke.py 24 PASS / 0 FAIL（ai-service 刻意未启动）
- [OK] silent-ai-server.py 真实验证读超时 → explain 得 5001（非 5002），日志 WARN AI 降级 reason=TIMEOUT
- [OK] 回归 g4-smoke.py 26 PASS，证明 StatsMapper.xml 增补未破坏既有聚合
- [OK] 敏感信息核查：AppSecret/MySQL 密码/AppID/JWT 明文密钥/用户明文密码均 0 命中
- [OK] 红线 anime_tracker 仍 20 表

### Status

[OK] **Completed**

### Next Steps

- 阶段 9 统计模块（补 StatsService/StatsController）
- 阶段 11 待办：application.yml 生产默认 com.vocab.mapper 由 DEBUG 改 INFO


## Session 7: backend 阶段 9：统计模块 + G6 契约回写
<!-- trellis-session: v=2 fp=921e45fedd3ab5a1 -->

**Date**: 2026-10-16
**Task**: backend 阶段 9：统计模块 + G6 契约回写
**Branch**: `main`

### Summary

实现契约 §7 热力图与掌握度两接口（SQL 聚合 + Java 补零/分档），并完成 G6 契约回写（接口详细设计 V1.1 + S3 V1.3）；277 测试全绿。

### Main Changes

- `common/MasteryRates`（**唯一真相源**）：`percent(mastered,total)`，`total<=0 → 100`；
  统计曲线（§7.2）与 AI 报告（§9.3）共用，`AiServiceImpl#buildStudyStats` 一并改调它。
- `StatsService`/`StatsServiceImpl` 只做「SQL 聚合 + Java 补零/分档」：
  `heatmap` 按 `check_in_date` 聚合、恒定 `weeks×7` 点（缺省 12、上限 52）、
  `level` 分档 `0 / 1–10 / 11–20 / 21+`；`mastery` 按 `study_date` 聚合、恒定 `days` 点
  （缺省 30、上限 365）、`masteryRate` 为**当日快照**（整条曲线同值）。
- `Constants` 增 `DEFAULT_HEATMAP_WEEKS=12` / `DEFAULT_MASTERY_DAYS=30` /
  `MAX_HEATMAP_WEEKS=52` / `MAX_MASTERY_DAYS=365`。
- **修 1 个真问题（规则一致性违反）**：`StatsMapper.xml#selectMasteryDaily` 原用
  `DATE(create_time)`——**MySQL 专有函数**，违反「时间边界由 Java 传参」红线
  （同文件头注释自己就声明了该规则；H2 `MODE=MySQL` 恰好支持 `DATE()`，故测试全绿而漏检）。
  改为 SQL-92 `CAST(create_time AS DATE)`。
- **G6 契约回写（先文档后代码，提交 `29e8e5f`）**：`docs/接口详细设计.md`
  V1.0 → V1.1（20 处替换，390 → 498 行）+ `docs/S3-软件概要设计说明书-正文.md`
  V1.3（5 处替换）。落盘脚本 `.tmp/g6_contract.py` / `.tmp/g6_s3.py` 对**每处替换
  断言「恰好命中一次」**（文本漂移即 `FAIL ... 命中 N 次` 并 `exit(1)`），避免静默漏改；
  首跑还捕获并修正了草稿里语义写反的一处（「不存在…记录」应为「存在…记录」）。
  提交仅 2 文件，用 `git add --pathspec-from-file=.tmp/g6_paths.txt` 精确 add。


### Git Commits

| Hash | Message |
|------|---------|
| `a8be043` | feat(stats): 阶段9 统计模块——打卡热力图与掌握度曲线（契约 §7） |
| `29e8e5f` | docs(contract): 补齐管理端接口并记录 word_progress 偏离 |

### Testing

- [OK] mvn -o test → 277 tests, 0 failures, 0 errors（+27）
- [OK] stage9-stats-smoke.py 29 PASS / 0 FAIL（真实 MySQL 8.4.9 + 真实 HTTP，独立前缀 stage9*）
- [OK] 直连 pymysql 验证 CAST(create_time AS DATE) 在 MySQL 8.4.9 正确返回日期
- [OK] 回归 g4-smoke.py 26 PASS / g4-crossday.py 18 PASS / g5-smoke.py 24 PASS，全 0 FAIL
- [OK] G6 回写脚本每处替换断言恰好命中一次，20+5 处全部 OK
- [OK] 红线 anime_tracker 仍 20 表

### Status

[OK] **Completed**

### Next Steps

- 阶段 10 管理模块（契约 V1.1 §8 十个接口；前置已就位：JwtInterceptor 403+1007、WordBookService 三方法占位）
- 阶段 11 收尾（README/smoke-test.sh/prd 逐条自查）


## Session 8: backend 阶段 10-11：管理模块（G6）+ 收尾交付（G7）
<!-- trellis-session: v=2 fp=549ff8d3203b9ec3 -->

**Date**: 2026-10-16
**Task**: backend 阶段 10-11：管理模块（G6）+ 收尾交付（G7）
**Branch**: `main`

### Summary

Session summary was not supplied.

### Main Changes

- 阶段 10：契约 §8 十接口（4 个 Admin Controller + AdminService）
- 阶段 11：一键冒烟脚本、README 重写、clean-smoke-data、供应商登记

### Git Commits

| Hash | Message |
|------|---------|
| `c376ce2` | feat(admin): 阶段10 管理模块——词库/用户/监控十接口（契约 §8） |
| `624b978` | chore(release): 阶段11 收尾——一键自测/README/测试数据清理/供应商登记 |

### Testing

- [OK] mvn -o clean package → 326 tests 全绿
- [OK] smoke-test.sh → 143 用例 0 FAIL + ERROR=0
- [OK] 红线 anime_tracker 20 表未变；敏感信息 grep 0 命中

### Status

[OK] **Completed**

### Next Steps

- ai-service 子任务：key 到位后补 AI 成功路径验证


## Session 9: backend 任务归档与收尾（/trellis-finish-work）
<!-- trellis-session: v=2 fp=28f4a65375982739 -->

**Date**: 2026-10-16
**Task**: backend 任务归档与收尾（/trellis-finish-work）
**Branch**: `main`

### Summary

归档 10-16-backend-api（--skip-branch-validation，任务从未 PR-backed）并记录收尾会话；未改代码。

### Main Changes

- 归档任务 10-16-backend-api → .trellis/tasks/archive/2026-10/（c1c9d65，仅任务目录）
- 归档用 --skip-branch-validation：单 trunk 本地仓库、无远端/PR/worktree，branch 与 base_branch 同为 main
- 工作区 24 项脏路径（23 个 docs/logo/*.png 删除 + 1 个 docs/小程序注册信息*.md 删除）均为其它窗口改动，未纳入本任务提交

### Git Commits

| Hash | Message |
|------|---------|
| `c376ce2` | feat(admin): 阶段10 管理模块——词库/用户/监控十接口（契约 §8） |
| `624b978` | chore(release): 阶段11 收尾——一键自测/README/测试数据清理/供应商登记 |

### Testing

- [OK] 归档提交 c1c9d65 仅含 .trellis/tasks/** 19 项，docs/logo 0 项被卷入
- [OK] 收尾会话未改代码；阶段 11 末基线 mvn -o test = 326 tests, 0 failures, 0 errors

### Status

[OK] **Completed**

### Next Steps

- ai-service 子任务：DeepSeek key 到位后补 AI 成功路径端到端验证
- miniprogram 学生端 / admin-web 管理端为独立子任务（planning）

## Session 10: ai-service 规划（Phase 1 完成）
<!-- trellis-session: v=2 fp=ai-service-planning-2026-10-17 -->

**Date**: 2026-10-17
**Task**: 10-16-ai-service（AI 微服务）
**Branch**: `main`

### Summary

恢复 ai-service 子任务：补全 Phase 1 规划产物（原仅有 task.json + 空 prd.md），
完成 DeepSeek 供应商与 langchain 依赖的实测研究，产出 design.md / implement.md /
implement.jsonl / check.jsonl。**未写任何 ai-service 代码**（Phase 2 未启动）。

### Main Changes

- `research/01-deepseek-and-deps.md`（新建，360 行）：DeepSeek 现状 + 依赖闭包 + backend 消费口径实测
- `prd.md`（重写）：补 A 段（静态，不依赖 key）/ B 段（真实生成路径）/ C 段（与 backend 契约对齐）验收标准
- `design.md`（新建，480 行）：边界/契约/结构/配置/LLM 客户端/编排/错误/日志/测试/锁版/风险
- `implement.md`（新建，321 行）：12 阶段顺序清单 + 每阶段验证命令与闸口 + 回滚点 + 11 条已知陷阱
- `implement.jsonl` / `check.jsonl`：各 9 条上下文清单，`task.py validate` 通过

### Key Decisions

1. **版本基线 = `langchain 1.3.18`（用户指定）**：全家族升 1.x（core 1.6.12 / openai 1.7.2 / openai-sdk 3.33.0）。
2. **🔴 关键更正：1.x 的 `langchain` 元包不再依赖 SQLAlchemy**。
   实测 `langchain==1.3.18` 闭包 39 包逐名筛查数据库包 → NONE；
   依赖 SQLAlchemy 的是 **0.3.x**（`SQLAlchemy>=1.4,<3`）。
   → 原「必须避开元包」的结论**被推翻**，按用户要求正常安装 `langchain` + `langgraph`，红线依然成立。
3. **`langgraph 1.2.16` 是 langchain 1.3.18 的必需依赖**，仅用于 report 的多步编排
   （`StateGraph` + `TypedDict(total=False)`，node: digest→analyze→compose）；example/explain 保持 LCEL 单链。
4. **隔离 venv 验证**（本机全局是旧 `langchain 0.3.7`，不可顶替）：
   venv 内装 1.x 栈 EXIT=0，`SQLAlchemy` NOT INSTALLED，`StateGraph` 实跑通过。
5. **模型 `deepseek-flash`**（`deepseek-chat` 已于 2026-07-24 退役）+ **thinking 默认 disabled**
   （官方默认开启且与 `json_object` 冲突 → 空 content）。
6. 真实 key 注入方式：**写入 `ai-service/.env`**（用户选定；已实测被 gitignore）。
7. 本任务**包含真实端到端验收**（三接口真实调用 + backend 联调 `degraded:false`）。

### Testing

- [OK] `git check-ignore -q ai-service/.env` → exit 0（被忽略）；`.env.example` → exit 1（可入库）
- [OK] venv 装 `langchain 1.3.18` + `langchain-openai 1.7.2` + FastAPI 栈 → EXIT=0
- [OK] venv 内 `SQLAlchemy` NOT INSTALLED；4 个 langchain 包的 `requires()` 无数据库依赖
- [OK] `langgraph.graph.StateGraph` invoke/ainvoke 实跑通过
- [OK] LCEL `prompt | fake | StrOutputParser()` sync+async 实跑通过
- [OK] `task.py validate 10-16-ai-service` → implement.jsonl ✓ (9) / check.jsonl ✓ (9)
- [INFO] 未运行 ai-service 代码（尚不存在）

### Status

[PARTIAL] **Phase 1 规划完成**；Phase 2 待评审闸口后 `task.py start` 激活。

### Next Steps

- 等用户确认规划（评审闸口）→ `task.py start 10-16-ai-service`（status → in_progress）
- 索取 DeepSeek key 实际值 → 写入 `ai-service/.env`
- 按 `implement.md` 阶段 1-9 实施；阶段 7-8 做真实调用与 backend 联调

---

## Session 11: ai-service 实施完成（2026-10-17 12:00–12:20）

### Goal

按已修订的 design/implement 文档实施 `ai-service`（FastAPI + LangChain 1.3.18 + LangGraph + DeepSeek），
并完成真实 key 的端到端验收（阶段 7/8）。

### Decisions

1. **`max_tokens` 走 `extra_body`**：`langchain-openai 1.7.2` 把构造参数 `max_tokens=`
   改名成 `max_completion_tokens`，DeepSeek 不认；MockTransport 实测最终 body 顶层键为
   `['max_tokens','messages','model','response_format','stream','thinking']`。
2. **`create_app(*, settings=None, http_client=None)` 工厂**：lifespan 内建 `httpx.AsyncClient`，
   测试注入 MockTransport；退出时 `aclose()` 并清空依赖它的 chain 引用。
3. **`DEEPSEEK_API_KEY: SecretStr | None = None`**：缺 key 不崩启动，`/health` 报
   `llmConfigured:false`，调用期 503。
4. **langgraph 只给 report**：digest（纯 Python）→ 条件路由 → analyze → compose；
   example/explain 保持单步 LCEL。
5. **report 快速路径语义**：`topMistakeWords=[]` **且** `masteryRate==100` 才 0 次 LLM，
   文案写死「暂无明显薄弱错题」，禁止夸大成「已掌握全部内容」。
6. **`pytest.ini` 必须含 `asyncio_mode = auto`**：否则 async 用例静默 skip 且退出码 0（假绿）。
7. **lifespan 测试用 `app.router.lifespan_context(app)`**：裸 `ASGITransport` 不跑 lifespan。

### Implementation

- 35 个文件：`app/{main}.py`、`core/{config,logging,llm,errors}.py`、
  `schemas/{common,example,explain,report}.py`、
  `chains/{prompts,parsing,example_chain,explain_chain,report_graph}.py`、
  `routers/ai.py`、`tests/`（9 个测试文件）、`scripts/{verify_live,e2e_backend}.py`、
  `requirements.txt`、`pytest.ini`、`.env.example`、`README.md`。
- 依赖闭包 55 包，**DB 包为 NONE**（红线：AST 扫描 + requirements 断言双保险）。

### Testing

- [OK] `pytest -q` → **58 passed**（含真实 HTTP body 断言、deadline 取消、lifespan 两轮、
  解析容错矩阵、report 条件路由、架构红线）
- [OK] 红线测试有牙齿：临时插入 `import sqlalchemy` → 测试失败（已回滚）
- [OK] 真实 key 三接口：example 1.1s / explain 3.7s / report 2.4s（预算 12/25/50s）
- [OK] backend 端到端：`example`/`explain`/`report` 全部 `code:"0"` + `degraded:false`
- [OK] 反证（停 ai-service）：`example` → `degraded:true` + 兜底例句；
  `explain`/`report` → `5002`
- [OK] 日志零泄漏（无 key / 无请求体 / 无 wrongContext）
- [OK] 数据清理：g5 用户清零；`ai_vocab` 7 表 / `word` 22761 / `word_book` 3；
  `anime_tracker` 20 表未动

### Discovered Bug（测试抓到的真实缺陷）

缺 key 时 `lifecycle` 把 chain 置为 `None`，router 直接调用导致 `AttributeError`
被泛化成 **502**（而非 503）。已修：新增 `_require_chain()` 显式抛
`not_configured_error()`，并补两条走真实 lifespan 的回归测试。

### Status

[DONE] 阶段 1–8 全部完成；`implement.md` 37 条全部勾选。
待提交（用户选择阶段 7/8 都过后一次性提交）。

### Next Steps

- 更新 `handoff.md` 中 ai-service 状态
- 一次性提交（确认 `git show --stat` 无 `.env`、无 `docs/` 既有删除）


## Session 12: ai-service 复审修复与任务归档
<!-- trellis-session: v=2 fp=3480aaf594e8475d -->

**Date**: 2026-10-17
**Task**: ai-service 复审修复与任务归档
**Branch**: `main`

### Summary

复审发现 2 个 P0 缺陷（except BaseException 吞取消、APITimeoutError 误映射 502），修复并补牙齿验证过的回归用例；补齐 live 测试与真实验证证据；修正会自我命中的红线命令；任务归档。

### Main Changes

- 修复 except BaseException 吞掉 asyncio.CancelledError：改为 except Exception 并单独放行取消，收敛为 _handle() 上下文管理器
- 修复 openai.APITimeoutError 不被识别为超时：MRO 不含 TimeoutError，超时判据显式补上
- 新增 tests/test_live_deepseek.py（默认 skip）与 research/02-live-verification.md
- 新增 research/03-implementation-review.md 记录复审结论
- 修正 implement.md 红线 grep 会命中 requirements.txt 注释而永久失败

### Git Commits

| Hash | Message |
|------|---------|
| `9cc81fb` | fix(ai-service): 修正异常映射两处缺陷并补齐真实路径验证 |
| `dc67e36` | docs(handoff): ai-service 归档前同步最终验证数据与两条异常映射陷阱 |

### Testing

- [OK] pytest -q → 60 passed, 4 skipped
- [OK] DEEPSEEK_LIVE_TEST=1 → 4 passed；example 1048ms / explain 3453ms / report 2433ms
- [OK] 两条回归用例做回退验证：修复即绿、回退即红
- [OK] ruff F,E9,B 全通过；app/ 零数据库词、零驱动 import、无 print()、.env 未跟踪

### Status

[OK] **Completed**

### Next Steps

- 读 10-16-miniprogram-student 的 prd/design/implement/research 后 task.py start
- 实跑验证 wx-login：application-local.yml 已有真实 appid/secret，可能不再阻塞

---

## Session 13: 小程序学生端 S1–S10 全部完成与归档（2026-10-17）

### Goal

完成 `10-16-miniprogram-student`（P1）：微信原生小程序学生端 F1–F10，零第三方库，
六 tabBar（后因平台限制改五 tabBar），12 阶段计划 S1–S10。

### Main Changes

**S1–S2 骨架与登录**
- 11 个页面四件套、7 个 services、5 个 utils；`utils/request.js` 为唯一网络出口
- F1 登录/注册双 tab、微信一键登录、首登昵称引导（可跳过）

**S3–S9 十个功能页**
- F2 首页三卡（`Promise.allSettled` 隔离，卡片失败不污染其他卡）
- F3 词库分类筛选、加入/移出二次确认
- F4 学习逐词上报、卡片翻面、AI 例句（`degraded` 黄条）、进入定位第一个未学词
- F5 打卡（门槛 `finished >= total`，分母用 `plan.total` 不写死 20）
- F6 复习（SM-2 间隔、最末入完成页、空态「今日没有待复习内容🎉」）
- F7 错题本（AI 讲解与标记解耦，PRD 硬约束）
- F8 AI 报告（四种互斥终态，5003 不给重试）
- F9 统计（84 格热力图 `<view>` + 手绘 canvas 双 y 轴折线）
- F10 我的 + 关于（emoji 头像 6 选 1、昵称 ≤20 字、BSD 3-Clause 全文）

**S10 全链路验收**
- 编译器报 PRD 与平台硬冲突：`tabBar.list` 最多 5 项 → 与用户确认去掉「统计」
  tab，统计改普通页，入口两处（首页快捷入口 + 我的「学习统计」）
- 新增 3 个验证套件：`verify_e2e_flow.py`(49) / `verify_degraded_flow.py`(16) /
  `verify_all.py`（一键 8 套件）
- 新增 4 条静态门禁：`check_about_license` / `check_tab_navigation` /
  `check_tap_target_size` / `check_page_reachable`

### 修掉的真实缺陷（都不是测试写出来的，是跑出来的）

1. **`ERROR_CODES.PARAM_INVALID` 不存在**（应为 `VALIDATION_FAILED`）——JS 未定义属性
   静默 undefined，1005 分支永不命中，无学习记录的复习词永久卡列表。
   为此新增静态门禁扫所有 `ERROR_CODES.X` 引用是否真实存在。
2. **`advance()` 最后一词 fire-and-forget `loadPlan()`**——用户立刻点打卡时 `finished`
   还是旧值、按钮假禁用。改 `await`。回归验证：去 `await` → 变红 → 恢复 → 变绿。
3. **WXML 写 `explainMap[key].prop`**——key 不存在时读 undefined 属性，渲染空面板
   且无任何报错。改在 JS 拍平（`decorate()` + `syncExplainToList()`）。
4. **`storage.clearAll` 不存在**（只有 `clearAuth`）。
5. **13 处可点元素 <88rpx**（PRD 明文验收标准）——模拟器看不出，真机点不中才暴露。
6. **`.heat-months` 与 `.heat-grid` 列宽不一致**——逐列偏移。

### Testing

- [OK] 静态 18 项 / 接口 76 / 复习 18 / 错题 26 / 报告 11 / 统计 18 / 页面 98 / 全链路 49 = **314 项断言**
- [OK] 降级验收 16 项（停 ai-service）：F5 `degraded:true` 走 200+`"0"`、F7 报 5001/5002
      但标记已掌握仍可用、打卡统计全不受影响
- [OK] 401 防重入：并发 3 请求同时 401 → 只清一次 token、只跳一次登录页、只弹一次 toast
- [OK] `cli.bat preview` 退出码 0、零错误、产出预览二维码
- [OK] 所有新门禁均做牙齿验证（注入违规 → 变红 → 恢复 → 变绿）

### Git Commits

| Hash | Message |
|------|---------|
| `87fc561` | feat(miniprogram): S1 骨架+网络层 & S2 登录链路 F1 |
| `5ac4950` | feat(miniprogram): S3 首页 F2 + 词库 F3 |
| `7b9423c` | feat(miniprogram): S4 学习 F4 + 打卡 F5 |
| `d7fd471` | feat(miniprogram): S5 复习 F6 |
| `da0be3a` | feat(miniprogram): S6 错题本 F7 + AI 讲解 |
| `96b8450` | feat(miniprogram): S7 AI 学习报告 F8 |
| `b1a1aa7` | feat(miniprogram): S8 数据统计 F9 |
| `8ce3241` | fix(miniprogram): 静态门禁剥注释避免误报 |
| `636bd49` | feat(miniprogram): S9 我的页与关于页 F10 |
| `db04913` | chore(miniprogram): 删除误提交的临时补丁脚本 |
| `87f8892` | feat(miniprogram): S10 全链路验收 + 修 tabBar 平台限制冲突 |
| `75e6082` | docs(handoff): 小程序任务收尾 |

### Status

[OK] **Completed 并归档**至 `.trellis/tasks/archive/2026-10/10-16-miniprogram-student`

### Next Steps

- `10-16-admin-web`（P2）：React 18 + TS + Vite + AntD 5，4 页，依赖 `/api/admin/**` 10 接口（已就绪）
- 两项非阻塞待办：真机 `wx-login` 全链路、关于页「第 N 组」文案待用户提供


## Session 14: 管理端 admin-web S1–S7 完成并归档（4 页 / 225 断言 / 修无限重渲染真缺陷）
<!-- trellis-session: v=2 fp=4302bf7b9a9acf71 -->

**Date**: 2026-10-17
**Task**: 管理端 admin-web S1–S7 完成并归档（4 页 / 225 断言 / 修无限重渲染真缺陷）
**Branch**: `main`

### Summary

React18+TS+Vite+AntD5+axios 管理端 4 路由页；5 套验证脚本共 225 项断言（静态47/真源码27/真接口61/浏览器渲染38/CDP真实点击52）；tsc -b 零错误、build 通过；发现并修复 useSyncExternalStore 无限重渲染缺陷。

### Main Changes

- 登录页：非 ADMIN 不落 token（避免半个登录态导致后续全 403）
- 词库页：列表/删除/JSONL 导入 + 单词管理弹窗（同弹窗视图切换，避免双层 Modal）
- 用户页：分页/搜索/禁用启用；ADMIN 行 disabled；不做后端不支持的状态筛选
- 监控页：4 StatCard + 按功能拆分 + 降级事件；successRate 不乘 100
- utils/request.ts：axios 拦截器双路 code 分流（1006/1003 清 token、1007 只跳转不清）
- 修 useSyncExternalStore 快照返回新对象导致 Maximum update depth 白屏

### Git Commits

| Hash | Message |
|------|---------|
| `e464326` | feat(admin): React 管理端 4 页 + 5 套验证（225 项断言） |

### Testing

- [OK] verify_admin_web.py 47 项静态门禁（全部牙齿验证）
- [OK] verify_admin_frontend.py 27 项直接执行真实 TS 源码（esbuild + Node）
- [OK] verify_admin_api.py 61 项打真后端
- [OK] verify_admin_browser.py 38 项 headless Chrome 渲染
- [OK] verify_admin_e2e.py 52 项 CDP 真实点击（含 JSONL 上传与用户禁用联动）
- [OK] tsc -b 零错误；npm run build 通过（843KB chunk 警告为 AntD 全量，已记录为已知取舍）

### 修掉的真实缺陷（脚本跑到才发现的，文档抄不来）

1. **`useSyncExternalStore` 快照返回新对象 → 无限重渲染 → 整页白屏**

   `useAuth` 里原本写 `useSyncExternalStore(subscribe, getUserInfo)`，而
   `getUserInfo()` 内部 `JSON.parse` 每次都产生**新对象引用**。React 用 `Object.is`
   比较快照，永远判定「已变化」→ 无限 setState → 报
   `Maximum update depth exceeded`，整个管理端白屏。

   **修法**：快照函数改为返回 localStorage 里的**原始字符串**
   （`getUserRaw`），字符串相等天然稳定；再用 `useMemo` 按字符串解析出对象。
   这样下游 `useEffect` 的依赖数组也不会抖。

   **为什么门禁没抓到**：47 项静态检查全绿、27 项真实源码断言全绿、`tsc -b`
   零错误、`npm run build` 通过 —— 全都抓不到，因为它不是类型错误也不是逻辑
   错误，是 React 运行时的比较语义。**只有真实浏览器渲染才能暴露**。

   已补一条静态门禁：扫描**全部** `useSyncExternalStore(subscribe, X)` 调用，
   任何一处 X 为 `getUserInfo`/`getUser` 即红。注意必须取全部匹配——只取第一个
   会漏（第一处是 `getToken`，本来就安全）。

2. **CDP 按文本点按钮会点到外层 `div`**

   AntD 的 `<Form.Item>` 结构是
   `div.form-item > div.row > div.col > div.content > button > span`，
   **六层 `textContent` 完全相同**。按「textContent === 目标文案」找元素会命中
   最外层 `div`，`click()` 不触发提交，表现为「点了没反应」。

   **修法**：选择器限定 `button, a, .ant-dropdown-menu-item, .ant-popconfirm button`。

3. **AntD `Select` 必须派发 `mousedown` 才展开**

   `click()` 和 `mouseup` 都不行，`rc-select` 监听的是 `mousedown`。展开后
   选项是 `.ant-select-item-option` 而非 `button`。不展开就拿不到值，表单
   `required` 校验会静默拦住提交（`onFinish` 根本不执行）。

4. **`textarea` 的 value setter 不能取自 `HTMLInputElement.prototype`**

   React 受控输入要走原生 setter + 派发 `input` 事件。但 `textarea` 与 `input`
   是**不同类**，`Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,
   'value').set.call(textareaEl, v)` 会抛 `TypeError`。必须按 `tagName` 选
   `HTMLTextAreaElement.prototype`。

5. **等待条件用了「预览里就存在的文案」→ 假通过**

   断言「导入完成」原本等 `innerText.includes('跳过 1')`，但**上传预览的
   Alert 里本来就有「跳过 1 行」**，于是 `wait_for` 立刻返回真，而实际上导入
   按钮根本没点成功。改为等结果栏独有的「导入成功」标题。

   教训：**wait_for 的条件必须是「目标状态独有」的**，不能是「过程状态里也
   出现过的」文案。

### 验收实测数据（真实后端，非 mock）

- 词库：四级 7,508 / 六级 5,651 / 考研 9,602，合计 22,761
- 监控：AI 调用 85 次 → 成功率 92.9% → 降级 6 次 → 注册用户 354
- 降级反证：停 ai-service 后调 `/api/client/ai/example` →
  `totalCalls 85→86`、`degradedCount 6→7`、`successRate 92.9→91.9`、
  新事件 `reason=ERROR` / `result=返回库内例句`，前端 `degraded:true` 仍 200
- 导入：3 行（2 好 1 坏）→ 前端预览「共 3 行，可解析 2 行，跳过 1 行」→
  导入结果「成功导入 2 条，跳过 1 条」→ 后端新词书 `wordCount == 2`（坏行真被跳过）
- 禁用联动：disabled 某用户 → 其旧 token 请求立即 HTTP 401 + `code "1003"`

### Status

[OK] **Completed 并归档**至 `.trellis/tasks/archive/2026-10/10-16-admin-web`

### Next Steps

- S4 交付文档 docs/部署说明.md（唯一剩余工作）
- 非阻塞：真机 wx-login 全链路、关于页「第 N 组」文案
