# HANDOFF — AI 单词学习打卡（四端：后端 / ai-service / 小程序 / 管理端）

> 交接文档 · 最后更新 2026-10-17
> 仓库根：`C:\workspace\class\AiVocabularyCheckin` · 分支 `main`

---

## 1. 一句话现状

**P0 业务后端、P1 `ai-service/`、P1 `miniprogram/`、P2 `frontend/`（管理端）全部完成；只剩交付文档待补。**

- **backend**：33 个对外接口全部实现、7 张表 SQL 就绪、三本种子词书已导入真库（22761 词）、7 道闸口 G1–G7 全部通过、单测基线 **326 tests / 0 failures / 0 errors**。任务 `10-16-backend-api` 已归档。
- **ai-service**：35 文件 + 单测 60 passed / 4 skipped；三接口真实 key 实测约 1.0s / 3.5s / 2.4s（预算 12/25/50s）；停服反证降级 `degraded:true` / `5002` 已验。
- **miniprogram**：19 个验证脚本 / 314 断言 + 98 项页面流断言 + 16 项降级断言，编译零错误，任务已归档。
- **管理端 `frontend/`**：React 18 + TS + Vite + AntD 5，4 个路由页；**5 套验证脚本共 225 项断言全部通过**（其中 52 项是 Chrome 真实点击驱动，38 项是真实浏览器渲染，27 项直接执行 TS 源码，61 项打真后端，47 项静态门禁）；`tsc -b` 零错误、`npm run build` 通过。

**下一步只剩 S4 交付文档（`docs/部署说明.md`）**；四个子任务代码侧均已闭环。

---

## 2. 任务树与状态

| 任务 | 交付物 | 优先级 | 状态 |
|---|---|---|---|
| `10-16-aivocab-fullstack` | 全栈（父任务） | P0 | planning · **3/4 子任务完成** |
| `10-16-backend-api` | Spring Boot 后端 + SQL + 种子词库 | P0 | ✅ **completed，已归档** |
| `10-16-ai-service` | FastAPI 三个 AI 接口 | P1 | ✅ **completed（代码+单测+真实调用+联调）** |
| `10-16-miniprogram-student` | 微信小程序 F1–F10 | P1 | ✅ **completed（11 页 / 314 断言 / 编译零错误）** |
| `10-16-admin-web` | React 管理端 4 页 | P2 | ✅ **completed（4 页 / 225 断言 / build 通过）** |

任务元数据位于 `.trellis/tasks/<name>/`，含 `prd.md` / `design.md` / `implement.md` / `check.jsonl` / `research/`。

> ⚠️ **任务状态与工作区记忆**：`.trellis/workspace/zhaizzH/journal-1.md` 已记 14 个 session，`index.md` 同步。继续工作时先读 journal 的 Session 13/14 可快速恢复上下文。

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

## 4. 各子任务交付详情（4.1/4.2/4.3 已完成，4.4 待做）

### 4.1 `10-16-ai-service`（P1，✅ 已完成并归档）

- **工程**：`ai-service/` — FastAPI 0.115.5 + `langchain` 1.3.18 + `langgraph` 1.2.16 + DeepSeek。
- **3 个接口**：`POST /ai/v1/example`（自控 12s < backend 15s）/ `/explain`（25s < 30s）/ `/report`（50s < 60s）。
- **架构红线（已自动化）**：`tests/test_no_database_imports.py` AST 扫描 `app/**/*.py` 禁止任何 DB 驱动 import，并断言 `requirements.txt` 无 DB 包；实际安装闭包 55 包中 DB 包为 **0**。
- **契约**：字段与 `docs/接口详细设计.md` §9 逐字一致；测试断言响应字段集合「不多不少」。
- **启动**：`cd ai-service && .venv/Scripts/python.exe -m uvicorn app.main:app --port 8000`。
- **关键坑（勿回退）**：
  1. `max_tokens` **必须经 `extra_body`** 传——`langchain-openai 1.7.2` 会改名成 `max_completion_tokens`，DeepSeek 不认；
  2. `pytest.ini` 的 `asyncio_mode = auto` 必须有，否则 async 用例静默 skip 且退出码 0；
  3. lifespan 测试必须用 `app.router.lifespan_context(app)`，裸 `ASGITransport` 不跑 lifespan；
  4. `DEEPSEEK_THINKING_ENABLED=true` 会在启动校验阶段直接失败（刻意不留开关）；
  5. **禁 `except BaseException`**——会吞掉 `asyncio.CancelledError`，既把断开/停机翻译成 502，又破坏取消协议；
  6. **`openai.APITimeoutError` 不是 `TimeoutError` 子类**，超时判据必须显式列出它，否则单次超时错走 502。
- **验证脚本**：`ai-service/scripts/verify_live.py`（三接口直连）、`scripts/e2e_backend.py`（经 backend 中转，支持 `--negative` 验降级）；真实路径单测 `tests/test_live_deepseek.py`（默认 skip，`DEEPSEEK_LIVE_TEST=1` 才跑）。
- **单测**：60 passed + 4 skipped（live 用例默认跳过）；真实调用实测 example 1048ms / explain 3453ms / report 2433ms。
- **证据留存**：`.trellis/tasks/10-16-ai-service/research/02-live-verification.md`（真实响应正文 + 耗时 + 失败路径 + 降级反证）、`research/03-implementation-review.md`（复审结论）。

### 4.2 `10-16-miniprogram-student`（P1，✅ 已完成，9 次提交）

- 微信原生小程序，**五** tabBar（首页/学习/复习/错题/我的），F1–F10 全实现，11 个页面。
- **零第三方库**：热力图 = 84 个 `<view>` + 4 档 CSS 类；掌握度曲线 = 手写 `<canvas type="2d">` 双 y 轴折线。
- **分层铁律**：`pages/` 不调 `wx.request`、不拼 URL、不判 `code`；`services/` 只封装 API；`utils/request.js` 是唯一网络出口。
- **验证规模 314 项断言**（+ 16 项降级手动）：静态 18 / 接口契约 76 / 复习 18 / 错题 26 / 报告 11 / 统计 18 / 页面 98 / 全链路 49。
  一键：`cd miniprogram && PYTHONIOENCODING=utf-8 python scripts/verify_all.py`
- 编译：`cli.bat preview` 退出码 0、零错误、产出预览二维码。

**⚠️ PRD 与平台冲突（已决策，勿回退）**

PRD 原写 6 tab（含「统计」），微信 `tabBar.list` **硬上限 5 项**，编译器直接拒绝，
`custom: true` 自定义 tabBar 同样受 `list` 2–5 项限制，无法绕过。
已与用户确认：**去掉「统计」tab**，统计改为普通页，入口两处（首页快捷入口 + 我的页「学习统计」）。
`prd.md` 已同步改为 5 tab——否则后续验收会按错误基准判定。

**必须知道的坑（都踩过并已加门禁）**

1. **`ERROR_CODES.X` 拼写错了不报错**：JS 未定义属性静默 `undefined`，分支永不命中。
   实例：`ERROR_CODES.PARAM_INVALID` 不存在（应为 `VALIDATION_FAILED`），
   导致无学习记录的复习词永久卡在列表。（`check_error_code_refs`）
2. **WXML 里不能写 `map[key].prop`**：key 不存在时读 `undefined` 的属性，
   渲染空面板且**无任何报错**。一律在 JS 里拍平成扁平字段。（错题页 `decorate()`）
3. **`getApp()` mock 必须单例**：每次返新对象会让「读-改-写」缓存失效，缓存用例假失败。
4. **页面测试判进度用 `index` 不用 `finished`**：`finished` 中途恒 0（只最后刷新），当循环条件会跑满全部词。
5. **`advance()` 学完最后一词必须 `await loadPlan()`**：原先 fire-and-forget，
   用户立刻点打卡时 `finished` 还是旧值、按钮假禁用。
6. **F9 空态绝不用 `masteryRate`**：后端无数据时返 `100` 不是 `0`。判据用三个计数全 0。
7. **热力图按 `check_in_date` 聚合**：学了未打卡仍是 0 档。这是契约行为，不是 bug。
8. **静态门禁匹配前必剥注释**：否则解释性注释里提到错误写法就误报。（`strip_js_comments`）
9. **可点元素 ≥88rpx 是 PRD 明文验收标准**：模拟器上看不出，真机点不中才暴露。
   `check_tap_target_size()` 扫所有 `bindtap` 元素的 wxss 高度，修出 13 处真实违规。
10. **非 tab 页必须有入口**：从 tabBar 移出的页面若入口被删就是死页——编译过、单页测试过、
    用户永远打不开。`check_page_reachable()` 按登记表扫入口。
11. **`storage` 只有 `clearAuth`，没有 `clearAll`**。
12. **AI 降级是成功路径不是错误**：F5 例句 `degraded:true` 走 HTTP 200 + `code "0"`，前端必须显示黄条而非错误。
    F7/F8 才如实报 5001/5002。AI 挂掉时打卡、学习、错题、统计必须全部正常。
13. **F7 讲解失败不影响「标记已掌握」**：PRD 硬约束，有专门断言。

**静态门禁 18 项**（`scripts/verify_miniprogram.py`，每个都做过牙齿验证）：
分层红线（pages 不碰 wx.request / URL / code）、错误码拼写真实性、tab 跳转 API 正确性、
非 tab 页可达性、可点元素高度、打卡文案不写死 20、统计空态判据、关于页 BSD 版权与版本号等。

### 4.3 `10-16-admin-web`（P2，✅ 已完成）

**位置**：仓库根 `frontend/`（不是 `admin-web/`——PRD 定名为 `frontend/`）。

**技术栈**：React 18.3.1 + TypeScript 5.7.2 + Vite 6.4.4 + Ant Design 5.22.2 + axios 1.7.9 + react-router-dom 6.28.0。

**4 个路由页**：

| 路由 | 页面 | 关键约束 |
|---|---|---|
| `/login` | 登录 | 与小程序共用 `/api/client/auth/login`；**非 ADMIN 不落 token**（存了等于半个登录态，后续全 403） |
| `/wordbank` | 词库管理 | 列表 + 删除 + `查看单词` 弹窗 + JSONL 导入 |
| `/users` | 用户管理 | 分页 + 搜索 + 禁用/启用；**ADMIN 行按钮 disabled**（后端返 1005，前端主动预防）；**不做状态筛选**（实测后端忽略 `status` 参数） |
| `/monitor` | 系统监控 | 4 张 StatCard + 按功能拆分表 + 降级事件表；`successRate` 是**数字百分数**不乘 100 |

**分层铁律**（有静态门禁守着）：

```
pages/ components/  →  services/  →  utils/request.ts  →  后端
```

页面禁止 `fetch`/`axios`/拼 URL/判 `code`；service 只拼路径与参数；`utils/request.ts` 是唯一处理 `Result` 解包与错误分流的地方。

**类型纪律**：无 `any`、无 `@ts-ignore`、无 `as` 强转（唯一豁免 `utils/request.ts` 的 `unwrap<T>`，因为 axios 拦截器运行时改返回值而类型系统不知）。

**错误码分流**（`utils/request.ts` 双路：成功回调 + 错误回调都判，因为 HTTP 401/403 走 reject 分支）：

| code | 处理 |
|---|---|
| `1006` 登录失效 / `1003` 账号禁用 | `clearAuth()` + `location.replace('/login')` |
| `1007` 非管理员 | 跳登录页但**不清 token**（登录态是好的，只是角色不够；清了就无法区分「权限被撤」与「登录过期」） |
| 其他（1005/2001/2004/9001…） | 抛 `ApiError(message)`，由页面 `message.error` 展示 |

**5 套验证脚本（共 225 项断言）**：

| 脚本 | 项数 | 依赖 | 验什么 |
|---|---|---|---|
| `scripts/verify_admin_api.py` | 61 | 后端 8080 + admin 账号 | 真实接口契约：三种身份登录、books 裸数组、words 跨库分页、CRUD + wordCount 联动、禁用后旧 token 1003、导入 2 好 2 坏、监控字段集合 |
| `scripts/verify_admin_web.py` | 47 | 无 | 静态门禁：分层红线、类型安全、rowKey、DTO 对齐、依赖白名单、无硬编码凭据、useSyncExternalStore 快照稳定性 |
| `scripts/verify_admin_frontend.py` | 27 | 无（esbuild 打包真实 TS 源码） | 直接**执行真实 `request.ts`/`auth.ts`/`services/*.ts`**：token 注入、Result 解包、1003/1006/1007 分流、service 路径与参数、multipart 不手设 Content-Type |
| `scripts/verify_admin_browser.py` | 38 | Vite 5173 + 后端 + Chrome | headless dump DOM：路由守卫拦截、4 页真实渲染、数字来自接口、无「Maximum update depth」 |
| `scripts/verify_admin_e2e.py` | 52 | Vite 5173 + 后端 + Chrome | CDP 真实点击：填表单登录、刷新不丢登录态、搜索、新增/删除单词 + wordCount 联动、**真实 JSONL 上传预览+导入**、禁用用户后其请求 1003、清 token 被踢回登录 |

**踩过的 4 个真缺陷（脚本抓到的，值得记住）**：

1. **`useSyncExternalStore` 快照返回新对象 → 无限重渲染**：`getUserInfo()` 每次 `JSON.parse` 都产生新对象，React 判定快照一直变 → `Maximum update depth exceeded`，整页白屏。**修法**：快照返回 localStorage 里的**原始字符串**，用 `useMemo` 按字符串解析。静态门禁和单元测试都抓不到，只有真实浏览器能抓。
2. **CDP 点按钮要选 `button` 而非 `div`**：AntD 的 `form-item > row > col > content > button > span` 五层 `textContent` 完全相同，按文本找会命中外层 `div`，`click()` 不提交表单。
3. **AntD `Select` 必须派发 `mousedown` 才展开**（不是 `click`），选项是 `.ant-select-item-option` 而非 `button`。
4. **`textarea` 的 value setter 必须取自 `HTMLTextAreaElement.prototype`**：用 `HTMLInputElement.prototype` 的 setter `call` 会抛 `TypeError`。

**已知取舍**：

- `npm run build` 有 843KB chunk 警告 —— AntD 全量引入，未做按需拆包。管理端是内网低频使用，可接受。
- 前端不设 `Content-Type`（multipart）—— 手设会丢 `boundary`，后端解析失败（实测返 9001 而非 2004）。
- `createTime` 原样展示不做 `new Date()` —— Safari 对 `yyyy-MM-dd HH:mm:ss` 不容错。

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
| ai-service 地址 | `http://127.0.0.1:8000`（✅ 已实现，`/health` 返回 `llmConfigured:true`） |

### 已提供 / 未提供

| 凭据 | 状态 |
|---|---|
| 微信 AppID / AppSecret | ✅ 已提供（已配入本地 `application-local.yml`） |
| DeepSeek API Key | ✅ 已配（`ai-service/.env`，已 gitignore）；真实调用实测 example 1.0s / explain 3.5s / report 2.4s |
| JWT_SECRET | 本地已生成（`application-local.yml`，不入仓） |

### 已知环境坑（踩过，勿重复）

1. **GBK 终端下不要用 `grep -c` 统计中文输出**——会数错（曾把 46 PASS 数成 47）。改用 python 解析统计。
2. **U+2212 数学减号 `−` 在 GBK 控制台会 `UnicodeEncodeError`**——脚本内统一用 ASCII `-`。
3. `mvn clean` 前先确认没有旧 java 进程持着 jar（曾因 PID 13500 失败）。`taskkill //PID <pid> //F` 杀进程。
4. 起服务前查 8080 是否被旧 jar 占用。
5. JDBC 连接串 `characterEncoding=utf8mb4` 非法，须写 `UTF-8`。
6. `@DynamicPropertySource` 的属性只解析一次——需要按用例变化的配置要手工 `new` 服务，不能靠它。

---

### 管理端账号

| 项 | 值 |
|---|---|
| 用户名 | `admin` |
| 密码 | `Admin@12345` |
| userId / role | `444` / `ADMIN` |
| 创建方式 | `PYTHONIOENCODING=utf-8 python scripts/create-admin.py`（幂等；BCrypt 强度 10，对齐 `AuthServiceImpl.BCRYPT_STRENGTH`） |
| 提权已有账号 | `python scripts/create-admin.py --promote <username>` |

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

# ── 小程序验证（需先起后端 8080 + ai-service 8000 + MySQL）──
cd miniprogram
PYTHONIOENCODING=utf-8 python scripts/verify_all.py       # 一键 8 套件，314 断言

# 单独跑（调 bug 时用）
PYTHONIOENCODING=utf-8 python scripts/verify_miniprogram.py   # 18 项静态门禁（无需服务）
PYTHONIOENCODING=utf-8 node  scripts/verify_page_flow.js      # 98 项页面逻辑
PYTHONIOENCODING=utf-8 python scripts/verify_e2e_flow.py      # 49 项 F1→F10 全链路
PYTHONIOENCODING=utf-8 python scripts/verify_degraded_flow.py # 16 项降级（须先停 ai-service）

# ── 小程序编译（需开发者工具开启服务端口）──
# 若 CLI 报 "IDE service port disabled"，用该参数重启 IDE 一次：
#   "/c/software/ide/微信web开发者工具/微信开发者工具.exe" --enable-service-port --cli --remote-port 3799
cd "/c/software/ide/微信web开发者工具"
./cli.bat preview --project "C:\\workspace\\class\\AiVocabularyCheckin\\miniprogram" --desc "验证"
#   退出码 0 + 末尾有二维码 = 编译通过
```

---

```bash
# ── 管理端（frontend/）──
# 起开发服务器（需后端 8080 已启动；Vite 5173 strictPort）
cd frontend && npm install && npm run dev

# 前提：一次性创建管理员账号（幂等）
PYTHONIOENCODING=utf-8 python scripts/create-admin.py

# 5 套验证（共 225 项断言），按依赖从轻到重
PYTHONIOENCODING=utf-8 python scripts/verify_admin_web.py       # 47 项静态门禁（无需服务）
PYTHONIOENCODING=utf-8 python scripts/verify_admin_frontend.py  # 27 项执行真实 TS 源码（无需服务）
PYTHONIOENCODING=utf-8 python scripts/verify_admin_api.py       # 61 项真实接口（需后端 8080）
PYTHONIOENCODING=utf-8 python scripts/verify_admin_browser.py   # 38 项浏览器渲染（需 5173 + 8080 + Chrome）
PYTHONIOENCODING=utf-8 python scripts/verify_admin_e2e.py       # 52 项 CDP 真实点击（需 5173 + 8080 + Chrome）

# 类型检查 + 生产构建
cd frontend && npx tsc -b && npm run build

# 注意：GBK 控制台必须带 PYTHONIOENCODING=utf-8，否则中文断言输出乱码/解码报错
```

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
|---|---|---|---|
| `wx-login` 端到端未验 | appid + secret 均有效（微信返回 `40029 invalid code` 证明凭证正确），但真机 `jscode2session` 全链路需真实微信环境 | 真机调试时验；当前账号密码登录链路已全绿 |
| 关于页「第 N 组」文案 | 待用户提供，现为占位（`groupText: ''`，WXML 用 `wx:if` 隐藏，不影响渲染） | 用户给出后填 `about.js` |
| PRD 6 tab → 5 tab | 平台硬限制，已决策并回写 prd.md | 已解决，勿回退 |
| 需求清单 §7 与 S3 V1.2 冲突 | 见 §8 警告 | 回写需求清单（低优先） |
| 归档任务元数据陈旧 | `description` 仍写 27 接口 | 可忽略（已归档，本就不该再改） |
| 管理端构建包偏大 | `npm run build` 有 843KB chunk 警告（AntD 全量引入，未按需拆包） | 内网低频使用，可接受；如要优化引入 `babel-plugin-import` 或 vite 手动分块 |
| 交付文档未写 | `docs/部署说明.md` 未写（S4 最后一项） | 唯一剩余工作 |

---

## 10. 交接检查清单

接手后建议按序确认：

- [ ] `cd backend && mvn -o test` → 326 tests / 0 failures / 0 errors
- [ ] `DBPASS=<REDACTED_DB_PASSWORD> bash backend/scripts/smoke-test.sh` → exit 0
- [ ] 查 `ai_vocab`：`word_book` 3 行、`word` 22761 行
- [ ] 查 `anime_tracker` 20 表未变
- [ ] 起后端 8080 + ai-service 8000，`cd miniprogram && PYTHONIOENCODING=utf-8 python scripts/verify_all.py` → 8 套件全绿
- [ ] 微信开发者工具 `cli.bat preview` → 退出码 0 + 二维码
- [ ] 读 `.trellis/workspace/zhaizzH/journal-1.md` 恢复上下文
- [ ] 读 `docs/接口详细设计.md`（V1.2）§9 后再动 AI 相关代码
- [ ] 确认 `git status` 里 `docs/logo/` 删除**非本任务引入**（其它窗口的改动，勿暂存）

---

## 11. 尚待解决的外部依赖（阻塞项汇总）

1. **无硬阻塞** — backend / ai-service / miniprogram 均可运行、可复现、可验证。
2. 仅剩两项非阻塞待办：真机 `wx-login` 全链路、关于页「第 N 组」文案。
3. `admin-web`（P2）尚未开工，依赖的 `/api/admin/**` 10 个接口已就绪。

---

*本文档记录截止 HEAD `87f8892` 的真实状态。所有数字（测试数、词数、断言数）均为实测值，非估算。*
