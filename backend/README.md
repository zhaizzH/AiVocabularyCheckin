# vocab-backend — AI 单词学习打卡 · 业务后端

Spring Boot 3.5 + MyBatis-Plus + MySQL 8 的业务后端。全系统**唯一**连接
MySQL 的服务；AI 生成能力由独立的 `ai-service`（FastAPI）提供，本服务只做
调用方与降级兜底。

> 状态：**全部 33 个对外接口已实现并自测通过**（认证 5 / 词库 4 / 学习 4 /
> 复习 2 / 错题 3 / AI 3 / 统计 2 / 管理 10）。一键自测见
> `backend/scripts/smoke-test.sh`；测试基线 `mvn -o test` = **326 tests 全绿**。

---

## 1. 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | 实测 21.0.11 通过 |
| Maven | 3.9+ | 实测 3.9.16 通过；离线构建需本地仓库已就绪（`mvn -o`） |
| MySQL | 8.0+ | 实测 8.4.9；需 `utf8mb4` 字符集 |
| Python | 3.8+ | 仅用于运行冒烟脚本（只用标准库）；`g4-crossday.py` / `stage10-admin-smoke.py` / `verify-schema-entities.py` 另需 `pymysql`（`pip install pymysql`） |

## 2. 首次运行步骤

### 2.1 建库

```sql
CREATE DATABASE IF NOT EXISTS ai_vocab
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
```

### 2.2 建表（迁移脚本）

表结构在 `src/main/resources/db/` 下，**必须按序号顺序执行**：

| 脚本 | 内容 |
|---|---|
| `V001__init.sql` | 6 张表：`user` / `word_book` / `word` / `user_word_book` / `study_record` / `mistake_entry` |
| `V002__word_progress.sql` | 第 7 张表 `word_progress`（复习调度状态，见《S3》§7 与 design.md 的偏离记录） |

```bash
# 两种方式任选
mysql -u root -p ai_vocab < backend/src/main/resources/db/V001__init.sql
mysql -u root -p ai_vocab < backend/src/main/resources/db/V002__word_progress.sql
```

脚本用 `CREATE TABLE IF NOT EXISTS`，**可重复执行**（便于课设演示）。
后续新增表请追加 `V003__xxx.sql`，不要改动已有脚本（与 Flyway 命名习惯保持一致，
本项目暂不引入 Flyway，由人工按序执行）。

> DDL 逐字对齐《S3-软件概要设计说明书》§7：字段宽度、索引名（`idx_<表>_<列>` /
> `uk_<表>_<列>`）均一致；**不加数据库级外键**（关联列建索引替代）；
> **时间列不带 DB 默认值**——由 ORM 的 `MetaObjectHandler` 单套填充，避免
> 「数据库和 Java 各填一次、时区/精度不一致」的双写分叉。

### 2.3 配置本地凭据

真实凭据**不入库**。从模板复制一份：

```bash
cp src/main/resources/application-local.yml.example \
   src/main/resources/application-local.yml
```

然后填入 MySQL 密码与本机 JWT 密钥（`application-local.yml` 已被
`.gitignore` 排除，不会提交）。

### 2.4 导入三本种子词书

词库源文件已落盘在仓库根的 `data/vocab-source/`（`CET4.jsonl` 7508 行 /
`CET6.jsonl` 5651 行 / `KAOYAN.jsonl` 9602 行 + `LICENSE`），**不需要下载**。

```bash
# 一次性执行；开关默认 false，幂等（已导入则跳过）
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments=--vocab.seed=true
```

导入后应能查到：`word_book` 3 行，`word_count` 分别 **7508 / 5651 / 9602**，
`word` 表共 **22761** 行。

> 大数据源（`data/vocab-source/*.jsonl` 约 20MB）留在本地不入库，仅
> `LICENSE` 入库（上游 `github.com/KyleBing/english-vocabulary`，BSD 3-Clause）。

### 2.5 构建与启动

```bash
mvn -o clean package                                   # 构建 + 跑全部单测
java -jar target/vocab-backend-1.0.0.jar --spring.profiles.active=local
# 或：mvn spring-boot:run -Dspring-boot.run.profiles=local
```

启动后服务监听 `http://127.0.0.1:8080`。

### 2.6 一键自测（推荐）

```bash
DBPASS=<你的 MySQL 密码> bash backend/scripts/smoke-test.sh
```

脚本会自动：拉起后端（若未运行）→ 依次跑 5 个 HTTP 冒烟脚本
（G4 核心闭环 / G4 跨日规则 / G5 AI 降级 / 阶段 9 统计 / 阶段 10 管理）→
跑 `mvn -o test` → 汇总 PASS/FAIL 与服务日志 ERROR 数。

**前置**：AI 降级是一等命题，要求 `127.0.0.1:8000`（ai-service）**不可达**；
脚本会检测并拒绝在有 ai-service 监听时继续。

可用的环境变量：`DBPASS`/`DB_PASSWORD`（必填）、`DB_HOST`/`DB_PORT`/`DB_NAME`/
`DB_USERNAME`、`BASE`（后端地址）、`SKIP_UNIT=1`（跳过 mvn test）、
`KEEP_SERVICE=1`（不关闭脚本启动的后端）。

### 2.7 手工自测示例
```bash
# 无 token 访问受保护接口 -> HTTP 401 + {"code":"1006",...}
curl -i http://127.0.0.1:8080/api/client/auth/profile

# CORS 预检放行 -> HTTP 200
curl -i -X OPTIONS http://127.0.0.1:8080/api/client/auth/profile \
  -H 'Origin: http://localhost:5173' \
  -H 'Access-Control-Request-Method: GET'
```

## 3. 环境变量清单

所有敏感项**无默认值**，必须显式提供，否则启动失败（fail-fast）——
这是刻意设计：宁可起不来，也不要一个带开发默认密钥的实例被误部署。

| 变量 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `JWT_SECRET` | ✅ | 无 | HS256 签名密钥，≥ 32 字节。`openssl rand -base64 48` |
| `DB_PASSWORD` | ✅ | 无 | MySQL 密码 |
| `DB_HOST` | — | `127.0.0.1` | |
| `DB_PORT` | — | `3306` | |
| `DB_NAME` | — | `ai_vocab` | |
| `DB_USERNAME` | — | `root` | |
| `AI_SERVICE_BASE_URL` | — | `http://127.0.0.1:8000` | AI 微服务地址 |
| `WX_APPID` / `WX_SECRET` | — | 空 | 留空则 `wx-login` 返回 1004，账号密码登录不受影响 |
| `WX_CODE2SESSION_URL` | — | 微信官方地址 | 可指向桩服务做联调 |
| `CORS_ALLOWED_ORIGINS` | — | `localhost:5173,127.0.0.1:5173` | 管理端来源，逗号分隔 |

> 本地开发可直接写进 `application-local.yml`，无需导出环境变量。

## 4. 项目结构

```
src/main/java/com/vocab/
├── VocabApplication.java          启动类
├── common/                        与框架无关的公共契约
│   ├── Result.java                统一响应 {code,message,data}，成功码 "0"
│   ├── ErrorCode.java             错误码枚举（1xxx 认证 / 2xxx 词库 / 3xxx 学习 /
│   │                              4xxx 错题 / 5xxx AI / 9xxx 系统）
│   ├── BizException.java          业务异常（所有可预期失败都抛它）
│   ├── PageResult.java            统一分页 {list,total,page,size}
│   ├── Pages.java                 分页参数归一（上限 500）
│   ├── Constants.java             业务常量（每日 20 词、复习间隔 1/3/7/15 天、角色…）
│   ├── MasteryRates.java          掌握率唯一真相源（分母 0 取 100）
│   ├── SuccessRate.java           成功率唯一真相源（分母含降级、无调用 100）
│   └── GlobalExceptionHandler.java 全局异常 → Result 转换
├── entity/                        7 张表的实体（唯一持有 @TableLogic 的地方）
├── mapper/                        MyBatis-Plus Mapper；复杂聚合写 XML
├── dto/request|response/          对外数据契约，绝不暴露实体
├── client/  WxApiClient.java      微信 code2Session 调用（含超时与失败翻译）
├── ai/                            AI 中转：客户端、超时判定、内存指标
│   ├── AiServiceClient.java       三功能分别 15s/30s/60s 显式超时
│   ├── AiCallException.java       isTimeout() 为超时判定的唯一真相源
│   ├── AiMetrics.java             调用/降级计数 + 最近 100 条降级事件（内存）
│   └── dto/                       与 ai-service 的内部传输结构
├── service/ + service/impl/       业务规则与算法（streak、复习间隔、降级矩阵）
├── runner/VocabSeedRunner.java    种子导入开关（--vocab.seed=true）
├── controller/                    用户端 controller（5 个）
│   └── admin/                     管理端 controller（4 个，/api/admin/**）
└── config/
    ├── MybatisPlusConfig.java     分页插件 + create_time/update_time 自动填充
    ├── ClockConfig.java           注入 java.time.Clock（测试可固定时间）
    ├── JacksonConfig.java         日期格式与序列化约定
    ├── JwtUtil.java               JWT 签发与解析（jjwt 0.12 API）
    ├── JwtInterceptor.java        鉴权拦截器（401/403、禁用即时生效、/api/admin 校验 ADMIN）
    ├── UserContext.java           当前登录用户 ThreadLocal
    ├── UserStatusValidator.java   查库校验账号状态的扩展点
    ├── WebMvcConfig.java          拦截器注册 + CORS
    └── RestTemplateConfig.java    调用 ai-service 的 HTTP 客户端（显式超时）
resources/
├── db/V001__init.sql, V002__word_progress.sql
└── mapper/*.xml                  仅用于 Wrapper 表达不了的聚合查询
scripts/                          冒烟与校验脚本（见 §7）
```

## 5. 接口总览（33 个）

| 分组 | 数量 | 路径前缀 |
|---|---|---|
| 认证 | 5 | `/api/client/auth`（register / login / wx-login / profile GET / profile PUT） |
| 词库 | 4 | `/api/client/books` |
| 学习 | 4 | `/api/client/study`（plan / record / checkin / streak） |
| 复习 | 2 | `/api/client/review`（today / record） |
| 错题 | 3 | `/api/client/mistakes` |
| AI | 3 | `/api/client/ai`（example / explain / report） |
| 统计 | 2 | `/api/client/stats`（heatmap / mastery） |
| 管理 | 10 | `/api/admin`（books / words / users / monitor） |

逐字段口径以 `docs/接口详细设计.md`（**V1.1**）为唯一依据。

## 6. 契约约定（关键，改动前务必确认）

这些约定是前端（小程序 + 管理端）的硬依赖，改动即破坏：

1. **统一响应结构** `{code, message, data}`；`code == "0"` 表示成功，
   前端一律用 `code !== "0"` 判断失败。
2. **业务失败 HTTP 状态码仍为 200**，错误通过 `code` 表达。
   仅以下情况保留真实 HTTP 状态：
   - `401` 鉴权失败（token 缺失/无效/过期/账号禁用）
   - `403` 非管理员访问 `/api/admin/**`
   - `404` / `405` / `415` 路由层错误（接口不存在 / 方法不支持 / 媒体类型不支持）
3. **所有列表接口必须分页**，参数 `page`（默认 1）、`size`（默认 20，上限 500）。
4. **绝不对外暴露实体**，一律在 Service 边界转换为 DTO
   （防泄露 `password_hash`、`deleted`）。
5. **Controller 只做参数校验 + 调用一个 Service 方法 + 包装返回**，
   不含业务逻辑，不直接调 Mapper。
6. **时间边界由 Java 传参**：SQL 里不写 `CURDATE()` / `NOW()` / `DATE()`
   等 MySQL 专有函数，以保证 H2 测试与 MySQL 生产语义一致。

### 错误码总表

| 段 | 码 | 含义 |
|---|---|---|
| 1xxx | 1001 / 1002 / 1003 / 1004 / 1005 / 1006 / 1007 | 用户名占用 / 用户名或密码错误 / 账号禁用 / 微信登录失败 / 参数校验失败 / 登录失效 / 无权访问 |
| 2xxx | 2001 / 2002 / 2003 / 2004 | 词书不存在 / 未选择该词书 / 词书已加入 / 导入文件格式错误 |
| 3xxx | 3001 / 3002 / 3003 | 今日已打卡 / 今日无学习计划 / 计划未完成 |
| 4xxx | 4001 / 4002 | 错题不存在 / 错题已掌握 |
| 5xxx | 5001 / 5002 / 5003 | AI 超时 / AI 异常 / 学习数据不足 |
| 9xxx | 9001 / 9002 / 9003 / 9004 | 系统繁忙 / 接口不存在 / 方法不支持 / 不支持 Content-Type |

## 7. 脚本清单（`backend/scripts/`）

| 脚本 | 用途 | 依赖 |
|---|---|---|
| `smoke-test.sh` | **一键自测**：拉起服务 + 跑全部冒烟 + `mvn test` + 汇总 | `DBPASS` |
| `g4-smoke.py` | G4 核心闭环（学习/打卡/复习/错题）26 用例 | 服务 |
| `g4-crossday.py` | G4 跨日规则（streak 递增/重置、跨天复习）18 用例 | `DBPASS` |
| `g5-smoke.py` | G5 AI 中转与降级（要求 ai-service 不可达）24 用例 | 服务 |
| `silent-ai-server.py` | 哑服务（接受连接但永不响应），用于压短超时验证 5001 | 无 |
| `stage9-stats-smoke.py` | 阶段 9 统计（热力图补零 / 掌握度曲线）29 用例 | 服务 |
| `stage10-admin-smoke.py` | 阶段 10 管理模块（§8 十接口）46 用例 | `DBPASS`+pymysql |
| `clean-smoke-data.py` | 清理冒烟测试遗留的测试用户/词书（幂等；`--dry-run` 可预览） | `DBPASS`+pymysql |
| `verify-schema-entities.py` | 实体 ↔ 真实 MySQL DDL 逐列核对（G2 复核） | `DB_PASSWORD` |
| `verify-h2-schema-parity.py` | H2 测试库 ↔ MySQL 生产库结构一致性（防测试假绿） | 本地 H2 文件 |

> 冒烟脚本只创建/清理自己前缀的测试数据（`g4_`/`g4cd_`/`g4n_`/`g5_`/`g5t_`/
> `stage9*`/`g6adm_`/`g6usr_` 用户与 `G6冒烟` 词书），**绝不触碰既有数据**。
> 其中 `stage10-admin-smoke.py` 与 `clean-smoke-data.py` 会自清理；`g4*`/`g5*`/
> `stage9*` 因用户无法自删（需登录态）会逐步累积，可用
> `DBPASS=*** python backend/scripts/clean-smoke-data.py` 批量清理。
> 所有脚本都接受后端地址为第一个参数（缺省读 `BASE` 环境变量，再缺省
> `http://127.0.0.1:8080`），故可用 `BASE=http://host:port` 指向远程实例。
> 仓库根的 `scripts/smoke-test.sh` 是转发入口。

## 8. 测试

```bash
mvn -o test                           # 全部单测/集成测试（H2 内存库，不依赖本机 MySQL）
mvn -o test -Dtest=JwtUtilTest        # 单个测试类
mvn -o test -Dtest='*Study*'          # 按模式
```

- 基线：**326 tests, 0 failures, 0 errors**。
- 测试类一律标注 `@Transactional`（回滚，避免污染共享 H2 造成其它用例假失败）。
- 涉及时间的用例注入 `testsupport/FixedClock`（默认 `2026-10-16`），不靠真实时钟。
- **降级测试禁用 Mock 伪造故障**：用真实对端（无人监听端口 / `SilentHttpServer` /
  `StubAiServer`），确保测的是真实网络路径而不是 Mock 的行为。
- H2 结构由 `src/test/resources/schema-h2.sql` 维护，与 MySQL DDL 的一致性可用
  `verify-h2-schema-parity.py` 核对。

## 9. 与 ai-service 的边界

- 本服务是**唯一**调用 `ai-service` 的一方；前端绝不直连 AI 服务。
- 三个 AI 接口全部有**显式超时**（例句 15s / 讲解 30s / 报告 60s），
  且失败时按契约降级（细节见 `docs/接口详细设计.md` §6）：
  - `example`：**绝不失败**——降级返回库内例句，HTTP 200 + `code:"0"` + `degraded:true`
  - `explain`：无兜底内容，返回 5001（超时）/ 5002（异常）
  - `report`：学习天数 < 3 直接 5003 **且不发 HTTP**；其余失败 → 5001/5002
- 降级事件记 WARN 日志（功能名、耗时、结果）并计入内存计数器，
  可通过 `GET /api/admin/monitor/ai-stats` 观测（含成功率、平均耗时、最近 100 条事件）。
- `ai-service` **不连 MySQL**——它无状态，数据一律由本服务传入。
