# HANDOFF — AI 单词学习打卡小程序（S4 程序包）

> 交接文档 · 最后更新 2026-10-17
> 仓库根：`C:\workspace\class\AiVocabularyCheckin` · 分支 `main` · HEAD `d9dc71c`

---

## 1. 一句话现状

**四端系统中，P0 的业务后端（`backend/`）与 P1 的 `ai-service/` 均已完成；`miniprogram/`、`frontend/` 尚未开工。**

- **backend**：33 个对外接口全部实现、7 张表 SQL 就绪、三本种子词书已导入真库（22761 词）、7 道闸口 G1–G7 全部通过、单测基线 **326 tests / 0 failures / 0 errors**。任务 `10-16-backend-api` 已归档。
- **ai-service**：35 文件 / 58 单测全绿 / 依赖闭包 55 包且 **DB 包为 0**；三接口真实 key 实测 1.1s / 3.7s / 2.4s；与 backend 端到端 `degraded:false` 已验，停服反证回到 `degraded:true` / `5002`。

**下一步是 `10-16-miniprogram-student`（P1）**，其后 `10-16-admin-web`（P2）。

---

## 2. 任务树与状态

| 任务 | 交付物 | 优先级 | 状态 |
|---|---|---|---|
| `10-16-aivocab-fullstack` | 全栈（父任务） | P0 | planning · **2/4 子任务完成** |
| `10-16-backend-api` | Spring Boot 后端 + SQL + 种子词库 | P0 | ✅ **completed，已归档** |
| `10-16-ai-service` | FastAPI 三个 AI 接口 | P1 | ✅ **completed（代码+单测+真实调用+联调）** |
| `10-16-miniprogram-student` | 微信小程序 F1–F10 | P1 | planning |
| `10-16-admin-web` | React 管理端 4 页 | P2 | planning |

任务元数据位于 `.trellis/tasks/<name>/`，含 `prd.md` / `design.md` / `implement.md` / `check.jsonl` / `research/`。

> ⚠️ **任务状态与工作区记忆**：`.trellis/workspace/zhaizzH/journal-1.md` 已记 11 个 session（663 行），`index.md` 同步。继续工作时先读 journal 的 Session 8/9 可快速恢复上下文。

---

## 3. 已完成部分（backend）详述

### 3.1 交付内容

```
backend/
├── pom.xml                     Spring Boot 3.5.6 / Java 17 / MyBatis-Plus 3.5.9 / jjwt 0.12.6 / H2(测试)
├── README.md                   完整使用说明（环境/建库/建表/种子/自测/接口/错误码/脚本）
├── src/main/java/com/vocab/    124 个 Java 源文件
├── src/main/resources/
│   ├── application.yml         全环境变量占位，JWT_SECRET 无默认值（fail-fast）
│   ├── application-local.yml.example
│   ├── db/V001__init.sql       6 张表
│   ├── db/V002__word_progress.sql  第 7 张表
│   └── mapper/*.xml            3 个 XML（仅 Wrapper 表达不了的聚合）
├── src/test/java/com/vocab/    24 个测试类 + 5 个测试支撑类，326 用例
└── scripts/                    10 个冒烟/校验脚本
```

### 3.2 接口总览（33 个，全部实现并自测通过）

| 分组 | 数量 | 前缀 |
|---|---|---|
| 认证 | 5 | `/api/client/auth` — register / login / wx-login / profile GET / profile PUT |
| 词库 | 4 | `/api/client/books` |
| 学习 | 4 | `/api/client/study` — plan / record / checkin / streak |
| 复习 | 2 | `/api/client/review` — today / record |
| 错题 | 3 | `/api/client/mistakes` |
| AI | 3 | `/api/client/ai` — example / explain / report |
| 统计 | 2 | `/api/client/stats` — heatmap / mastery |
| 管理 | 10 | `/api/admin` — books / words / users / monitor |

**逐字段口径以 `docs/接口详细设计.md`（V1.2）为唯一依据。**

### 3.3 数据库（7 张表，库 `ai_vocab`）

`user` / `word_book` / `word` / `user_word_book` / `study_record` / `mistake_entry` / `word_progress`

已实测的真实数据（**当前状态**）：

| 表 | 行数 |
|---|---|
| `word_book` | 3（id 6=四级核心词汇 7508 / id 7=六级核心词汇 5651 / id 8=考研核心词汇 9602） |
| `word` | **22761** |
| `user` / `user_word_book` / `study_record` / `mistake_entry` / `word_progress` | 0（测试数据已清理） |

### 3.4 七个闸口（全部通过）

| 闸口 | 内容 | 关键证据 |
|---|---|---|
| G1 | 环境与词库落地 | 3 份 JSONL 落盘 `data/vocab-source/`，0 坏行 |
| G2 | DDL 逐字对齐 S3 §7 | 55/55 列、14/14 索引核对通过；裸 INSERT 被拒（`1364`） |
| G3 | 词库模块 + 种子导入 | `word_book` 3 行、每本 COUNT(*) 全等、二次导入幂等；HTTP 冒烟 20/20 |
| G4 | 学习/打卡/复习/错题闭环 | `g4-smoke.py` 26 PASS / `g4-crossday.py` 18 PASS |
| G5 | AI 中转与降级 | `g5-smoke.py` 24 PASS（ai-service 刻意不启动）；哑服务验证 5001 |
| G6 | 契约回写（先改文档再写代码） | 接口设计 V1.0→V1.1→V1.2；S3 V1.2→V1.3→V1.4 |
| G7 | 收尾交付 | 一键自测 `smoke-test.sh` exit 0；**143 冒烟用例 0 FAIL** |

### 3.5 一键自测（现状可复现）

```bash
DBPASS=<MySQL 密码> bash backend/scripts/smoke-test.sh
```

脚本行为：拉起后端（若未运行）→ **前置断言 `127.0.0.1:8000`（ai-service）必须不可达**（降级是一等命题，有监听则拒绝并 exit 2）→ 依次跑 5 个冒烟脚本 → `mvn -o test` → 汇总 PASS/FAIL 与日志 ERROR 数。

实测结果：**G4 26 / 跨日 18 / G5 24 / 阶段 9 29 / 阶段 10 46 = 143 用例 0 FAIL**，`mvn -o test` 326 全绿，服务日志 ERROR=0。

---

## 4. 未完成部分（后续三个子任务）

### 4.1 `10-16-ai-service`（P1，✅ 已完成）

- **工程**：`ai-service/` — FastAPI 0.115.5 + `langchain` 1.3.18 + `langgraph` 1.2.16 + DeepSeek。
- **3 个接口**：`POST /ai/v1/example`（自控 12s < backend 15s）/ `/explain`（25s < 30s）/ `/report`（50s < 60s）。
- **架构红线（已自动化）**：`tests/test_no_database_imports.py` AST 扫描 `app/**/*.py` 禁止任何 DB 驱动 import，并断言 `requirements.txt` 无 DB 包；实际安装闭包 55 包中 DB 包为 **0**。
- **契约**：字段与 `docs/接口详细设计.md` §9 逐字一致；测试断言响应字段集合「不多不少」。
- **启动**：`cd ai-service && .venv/Scripts/python.exe -m uvicorn app.main:app --port 8000`。
- **关键坑（勿回退）**：
  1. `max_tokens` **必须经 `extra_body`** 传——`langchain-openai 1.7.2` 会改名成 `max_completion_tokens`，DeepSeek 不认；
  2. `pytest.ini` 的 `asyncio_mode = auto` 必须有，否则 async 用例静默 skip 且退出码 0；
  3. lifespan 测试必须用 `app.router.lifespan_context(app)`，裸 `ASGITransport` 不跑 lifespan；
  4. `DEEPSEEK_THINKING_ENABLED=true` 会在启动校验阶段直接失败（刻意不留开关）。
- **验证脚本**：`ai-service/scripts/verify_live.py`（三接口直连）、`scripts/e2e_backend.py`（经 backend 中转，支持 `--negative` 验降级）。

### 4.2 `10-16-miniprogram-student`（P1）

- 微信原生小程序，六 tabBar（首页/学习/复习/错题/统计/我的），F1–F10。
- **依赖真实后端**（不能只做静态页面）；`wx-login` 依赖 AppID，缺 AppID 时用账号密码登录跑通 F2–F10。
- AI 界面（F5/F7/F8）在无 key 时应验证**降级态**。
- 热力图用 84 个 view + 4 档 CSS 类（不引第三方库）；掌握度曲线用 `<canvas type="2d">` 手绘。

### 4.3 `10-16-admin-web`（P2）

- React 18 + TS + Vite + AntD 5，4 页：登录 / 词库管理 / 单词管理弹窗 / 用户管理 / 系统监控。
- **依赖 backend `/api/admin/**`（10 个接口，已就绪可对接）**。
- 导入 JSONL：前端预览前 20 行 → `multipart/form-data`（`file`/`name`/`category`）→ 展示「成功 N 条，跳过 M 条」。

### 4.4 交付文档（P2）

- S4 安装部署说明（含数据库脚本执行步骤、环境变量清单）——`backend/README.md` 已覆盖后端部分，总体部署说明（含四端协同）待补。

---

## 5. 关键红线与约束（**必须遵守**）

### 5.1 数据安全

1. **`anime_tracker` 库是红线，绝不触碰**（实测 20 张表，全程未变）。本项目库为 `ai_vocab`。
2. 冒烟脚本**只创建/清理自己前缀的测试数据**：`g4_` / `g4cd_` / `g4n_` / `g5_` / `g5t_` / `stage9*` / `g6adm_` / `g6usr_` 用户 + `G6冒烟` / `G4冒烟` / `冒烟` 词书，**绝不碰既有数据**。
3. 密钥不入库不入仓：`.env`、`application-local.yml` 已被 `.gitignore` 排除（已实测 `git ls-files` = 0）。

### 5.2 契约先行（**不可颠倒**）

**先改契约文档 → 再写代码。** 任何接口字段偏差必须先回写 `docs/接口详细设计.md`（含 §10 变更记录）与 `docs/S3-...-正文.md`（含 §1.6 版本表），再动代码。G6 就是这么做的。

### 5.3 代码规范（`.trellis/spec/backend/quality-guidelines.md`）

- Controller 不写业务逻辑、不返回实体；**禁** `System.out.println`；**禁**硬编码密钥/URL。
- 日志用 `{}` 占位符；**所有 AI 调用必须有超时 + 降级**；列表接口必分页。
- 密码只存 BCrypt；**禁**空 catch / catch 后原样抛出；ai-service **禁**连 MySQL。
- 新接口必走 `Result` 包装；每个请求 DTO 加 `@Valid`。

### 5.4 数据库规范（`.trellis/spec/backend/database-guidelines.md`）

- 动态条件优先 `LambdaQueryWrapper`；仅 Wrapper 无法表达才写 XML；所有列表用 `Page`。
- 表/列 snake_case；索引 `idx_<表>_<列>`、唯一键 `uk_<表>_<列>`；**不加 DB 级外键**但关联列建索引。
- 每表必有 `id` + `create_time` + `update_time`；面向用户数据逻辑删除，中间表物理删除。
- **不加 DB 级时间默认值**——由 ORM（`MetaObjectHandler`）单套填充。
- **时间边界由 Java 传参**：SQL 里不写 `CURDATE()` / `NOW()` / `DATE()` / `DATE_SUB()` 等 MySQL 专有函数，保证 H2 测试与 MySQL 生产同语义（曾因此改过 `StatsMapper.xml`）。

### 5.5 测试纪律

- 测试类一律 `@Transactional`（否则泄漏数据到共享 H2，连带其它测试假失败）。
- 涉及时间的用例注入 `testsupport/FixedClock`（默认 `2026-10-16`）。
- **降级测试禁用 Mock 伪造故障**：用真实对端（无人监听端口 / `SilentHttpServer` / `StubAiServer`）。

---

## 6. 环境与凭据

| 项 | 值 |
|---|---|
| JDK | 21.0.11（Temurin）— 编译级别 Java 17 |
| Maven | 3.9.16（离线构建用 `mvn -o`） |
| Python | 3.12.7（冒烟脚本只用标准库；部分需 `pymysql`） |
| Node | 24.18.0 |
| MySQL | 8.4.9，`utf8mb4`，InnoDB，含 `ONLY_FULL_GROUP_BY` / `STRICT_TRANS_TABLES` |
| H2 | 2.3.232（测试库） |
| 库 | `ai_vocab`（`anime_tracker` 不动） |
| 连接 | `127.0.0.1:3306` / `root` / 密码 `<REDACTED_DB_PASSWORD>` |
| 后端服务 | `http://127.0.0.1:8080` |
| ai-service 预期地址 | `http://127.0.0.1:8000`（**当前未实现**） |

### 已提供 / 未提供

| 凭据 | 状态 |
|---|---|
| 微信 AppID / AppSecret | ✅ 已提供（已配入本地 `application-local.yml`） |
| DeepSeek API Key | ❌ **未提供** → ai-service 真实生成路径无法验证 |
| JWT_SECRET | 本地已生成（`application-local.yml`，不入仓） |

### 已知环境坑（踩过，勿重复）

1. **GBK 终端下不要用 `grep -c` 统计中文输出**——会数错（曾把 46 PASS 数成 47）。改用 python 解析统计。
2. **U+2212 数学减号 `−` 在 GBK 控制台会 `UnicodeEncodeError`**——脚本内统一用 ASCII `-`。
3. `mvn clean` 前先确认没有旧 java 进程持着 jar（曾因 PID 13500 失败）。`taskkill //PID <pid> //F` 杀进程。
4. 起服务前查 8080 是否被旧 jar 占用。
5. JDBC 连接串 `characterEncoding=utf8mb4` 非法，须写 `UTF-8`。
6. `@DynamicPropertySource` 的属性只解析一次——需要按用例变化的配置要手工 `new` 服务，不能靠它。

---

## 7. 常用命令速查

```bash
# ── 构建与测试 ──
cd backend
mvn -o test                        # 全部测试（H2 内存库，不依赖本机 MySQL）→ 326 tests
mvn -o -q clean package -DskipTests   # 出 jar → target/vocab-backend-1.0.0.jar

# ── 起服务 ──
java -jar target/vocab-backend-1.0.0.jar --spring.profiles.active=local

# ── 一键自测（推荐，会自己拉起服务）──
DBPASS=<REDACTED_DB_PASSWORD> bash backend/scripts/smoke-test.sh
#   环境变量：DBPASS/DB_PASSWORD(必填)、DB_HOST/DB_PORT/DB_NAME/DB_USERNAME、
#            BASE(后端地址)、SKIP_UNIT=1(跳过 mvn test)、KEEP_SERVICE=1(不关服务)

# ── 清理冒烟测试遗留数据（独立脚本，刻意不接入 smoke-test.sh）──
DBPASS=<REDACTED_DB_PASSWORD> python backend/scripts/clean-smoke-data.py [--dry-run]

# ── 结构校验 ──
python backend/scripts/verify-schema-entities.py      # 实体 ↔ MySQL DDL 逐列核对
python backend/scripts/verify-h2-schema-parity.py     # H2 ↔ MySQL 结构一致性

# ── 种子导入（一次性，幂等）──
mvn spring-boot:run -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.arguments=--vocab.seed=true
```

---

## 8. 契约文档现状

| 文档 | 版本 | 说明 |
|---|---|---|
| `docs/接口详细设计.md` | **V1.2** | 唯一接口契约。33 接口逐字段口径。V1.1 补齐管理端 8.8–8.10 + 错误码 1006/1007/9002–9004；V1.2 补记 DeepSeek 供应商 |
| `docs/S3-软件概要设计说明书-正文.md` | **V1.4** | §7 数据表 7 张、§3.5 DeepSeek 供应商、§4.1.2 接口表 |
| `docs/S2-...-软件需求规格说明书-正文.md` | V1.1 | 需求基线 |
| `docs/需求清单-功能与规则.md` | — | **§7 明确一期不做**：支付/社交/真人教师、微信 code2Session、头像上传、发音音频、`word.definition` |
| `docs/学生端-需求分析与原型设计.md` | — | F1–F10 依据 |
| `docs/prototype/*.html` | — | 3 个原型（admin / miniprogram / states） |

### ⚠️ 一处已知文档不一致（不阻断，但需知）

`docs/需求清单-功能与规则.md` §7 把「微信 code2Session 快捷登录」列为**一期不做**，但 S3 V1.2 已决策「纳入一期」且 backend 已实际实现 `wx-login`。这是**历史决议的先后顺序问题**，实现侧以后者为准。若后续有人重读需求清单会困惑，建议回写需求清单对齐（未做）。

另一处：归档任务 `task.json` 的 `description` 写「27 个对外接口」，是**创建时的旧数字**，实际为 **33 个**（30 契约 + 3 管理端补齐）。属陈旧的元数据，不影响功能。

再一处（最轻）：`backend/README.md` §4 项目结构注释写「用户端 controller（5 个）」，实际为 **7 个**（Auth/Book/Study/Review/Mistake/Ai/Stats）+ 管理端 4 个 = 11 个 controller，与「33 接口」并不矛盾——只是注释里的个数未随阶段 8/9 新增 controller 同步更新。纯注释错字，无功能影响。

---

## 9. 风险与待办

| 项 | 说明 | 建议 |
|---|---|---|
| DeepSeek key 缺失 | ai-service 成功路径无法端到端验证 | 拿到 key 后补验；先完成代码 + mock 单测 |
| `wx-login` 端到端未验 | AppID 已提供，但未跑真实 code2Session 全链路 | 小程序端联调时一并验证 |
| 需求清单 §7 与 S3 V1.2 冲突 | 见 §8 警告 | 回写需求清单（低优先） |
| 归档任务元数据陈旧 | `description` 仍写 27 接口 | 可忽略（已归档，本就不该再改） |
| 管理端/小程序未开工 | 各为独立子任务 | 按 P1 → P1 → P2 顺序推进 |

---

## 10. 交接检查清单

接手后建议按序确认：

- [ ] `cd backend && mvn -o test` → 326 tests / 0 failures / 0 errors
- [ ] `DBPASS=<REDACTED_DB_PASSWORD> bash backend/scripts/smoke-test.sh` → exit 0
- [ ] 查 `ai_vocab`：`word_book` 3 行、`word` 22761 行、其余 0 行
- [ ] 查 `anime_tracker` 20 表未变
- [ ] 读 `.trellis/workspace/zhaizzH/journal-1.md` Session 8/9 恢复上下文
- [ ] 读 `docs/接口详细设计.md`（V1.2）§9 后再动 ai-service
- [ ] 确认 `git status` 里 24 项 `docs/` 删除**非本任务引入**（其它窗口的改动，勿误提交）

---

## 11. 尚待解决的外部依赖（阻塞项汇总）

1. **DeepSeek API Key** — 阻塞 ai-service 真实生成路径。
2. **无其它阻塞** — backend 已完全可运行可复现。

---

*本文档由 backend 任务收尾时生成，记录截止 HEAD `d9dc71c` 的真实状态。所有数字（测试数、词数、表数、用例数）均为实测值，非估算。*
