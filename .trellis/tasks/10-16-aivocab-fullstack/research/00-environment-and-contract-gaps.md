# 研究记录：环境探测、数据源与契约缺口

> 探测时间：2026-10-16 · 探测者：zhaizzH（AI 辅助）
> 目的：为 backend-api 子任务提供可复现的环境事实、词库数据事实与契约决策记录。

---

## 1. 本机环境事实（已实测）

| 组件 | 实测结果 | 结论 |
|---|---|---|
| JDK | `openjdk 21.0.11 LTS`（Temurin） | ✅ 可用。文档要求 JDK 17+，21 满足 |
| Maven | `Apache Maven 3.9.16` | ✅ 可用 |
| Python | `3.12.7` | ✅ 满足 3.10+ |
| Node / npm | `v24.18.0` / `11.16.0` | ✅ 满足 18+ |
| MySQL | **8.4.9**，`utf8mb4`，默认引擎 InnoDB | ✅ 可用 |
| MySQL 客户端 CLI | ❌ 未安装 `mysql` 命令 | 只用 JDBC / DBX MCP，不依赖 CLI |
| Docker | 未验证 | 不依赖，不用 compose |

### 1.1 MySQL 接入方式

- 本机 `127.0.0.1:3306`，账号 `root`，密码由组长提供（**不写入仓库**）。
- 已通过 DBX MCP（`http://127.0.0.1:5225/mcp`）验证：`root@localhost`，`SELECT @@version` → `8.4.9`。
- 现有库：`anime_tracker`（与本项目无关，**不得改动**）。本项目新建库 `ai_vocab`。
- `application.yml` 中连接信息全部走环境变量占位，仓库内不落明文密码。

### 1.2 网络可达性实测（决定依赖拉取策略）

| 目标 | 结果 | 说明 |
|---|---|---|
| `repo1.maven.org` | 200 | Maven Central 直连可用，无需配镜像 |
| `registry.npmjs.org` | 200 | npm 官方源可用 |
| `pypi.org/simple` | 200 | PyPI 可用 |
| `api.deepseek.com` | 401 | 可达；401 = 缺 key，说明网络通、路径正确 |
| `github.com` | 200（但 `git clone` 大仓库会超时） | 小请求通，大 packfile 传输不稳定 |
| `cdn.jsdelivr.net` | 000 | **不可用**，不能用作词库 CDN 源 |
| `unpkg.com` | 200 | 可用 |

**结论**：依赖一律从官方源拉取；词库数据必须在**本地落盘**（不能依赖运行时远程拉取），这与"导入接口 + 种子词库"的设计一致。

---

## 2. 词库数据源事实（已实测，非推测）

- 仓库：`github.com/KyleBing/english-vocabulary`，License = BSD 3-Clause。
- 采用目录：`full_line_jsonl/sentence/正序/`（与 S3 §8.2 决策一致）。
- **实测行数**（与文档 7508 / 5651 / 9602 完全吻合）：

| 文件 | 实测行数 | 对应词书 |
|---|---|---|
| `四级.jsonl` | **7508** | 四级核心词汇 |
| `六级.jsonl` | **5651** | 六级核心词汇 |
| `考研.jsonl` | **9602** | 考研核心词汇 |

### 2.1 单行结构（实测样本）

```json
{"word":"abruptly","us":"ə'brʌptli","uk":"ə'brʌptlɪ",
 "translations":[{"translation":"突然地","type":"adv"}],
 "phrases":[],
 "sentences":[{"sentence":"The path ends off abruptly.","translation":"这条路突然到头了。"}]}
```

### 2.2 字段映射（落实《接口详细设计》§8.1）

| 源字段 | 目标字段 | 处理规则 |
|---|---|---|
| `word` | `word.word` | 直接取值 |
| `uk` | `word.phonetic` | 直接取值（英式音标，契约要求英式） |
| `translations[]` | `word.translation` | 逐项拼 `"{type}. {translation}"`，多项用 `；` 连接 |
| `sentences[0].sentence` | `word.example` | 取第一条例句的英文 |
| — | `word.definition` | 源数据无英文释义，**留空**（S3 §8.2 已决策） |
| — | `word.audio_url` | 一期不填（需求清单 §7.4） |

**重要实测发现**：源数据**没有** `phonetic` 键，音标字段是 `us` / `uk` 两个。契约写的是 `uk→phonetic`，与此一致；但 `us` 字段一并保留在导入脚本里备查更稳妥。

**多义项样本**（验证拼接规则）：
`{"word":"murder","translations":[{"translation":"谋杀，凶杀；谋杀罪","type":"n"},{"translation":"谋杀；杀害","type":"v"}]}`
→ 期望 `translation` = `"n. 谋杀，凶杀；谋杀罪；v. 谋杀；杀害"`。

### 2.3 数据落盘方案

- 三份 JSONL 拉到本地 `data/vocab-source/`（gitignore，因单文件 7–10MB，三份约 24MB，不入库）。
- 提供 `scripts/fetch-vocab.sh`（带断点/重试说明）+ 备用：管理端导入接口上传。
- **不依赖 CDN**（jsdelivr 已实测 000）。

---

## 3. 契约缺口与决策记录

### 3.1 复习进度存储（S3 自相矛盾，已决策）

**矛盾事实**：
- 《接口详细设计》§4.1 响应含 `stage`（1–4）；§4.2 响应含 `nextReviewDate`。
- 《需求清单》§3.2：「按当前阶段设置下次复习日期」。
- 但 S3 §7 只定义 6 张表，**无任何字段**承载 stage / next_review_date；
  且 S3 §5.4 明确「复习列表为计算生成，**不设独立存储表**」。

**决策**：新增第 7 张表 `word_progress`。

```
word_progress(id, user_id, book_id, word_id, stage, next_review_date, learned, deleted, create_time, update_time)
```

**理由**：
1. `stage` / `next_review_date` 是**每词每用户**的单一当前状态，天然属于"进度表"语义；`study_record` 是逐次事件流水，把状态塞进去会产生同词多行、字段互相覆盖的脏语义。
2. 「今日待复习」变成一句 `WHERE next_review_date <= CURDATE() AND learned = 1`，**避免全量历史重放**。
3. 「每日计划的未学单词」直接 `learned = 0` 查询，无需 `NOT EXISTS` 关联子查询。
4. 这是对 S3 的**显式、有记录的偏离**（S3 说 6 张表 → 实为 7 张），需在 design.md 与 S3 后续修订中同步注明。

### 3.2 管理端接口缺口（契约不完整，已决策补齐）

**缺口事实**（对照 `admin-prototype.html`）：
- 词库管理页有「词书表格 + 删除按钮」，但契约 §8 **无** `GET /api/admin/books`、`DELETE /api/admin/books/{id}`。
- 监控页有「AI 调用统计卡片 + 降级事件列表」，但契约 §8 **无**任何监控接口。

**决策**：补齐 3 个接口，并**回写《接口详细设计.md》第 8 章 + 第 10 章变更记录**（该文档开篇规定"要改接口，先改本文档"）。

| 新增接口 | 用途 |
|---|---|
| `GET /api/admin/books` | 词书分页列表（词书名/分类/单词数/创建时间） |
| `DELETE /api/admin/books/{bookId}` | 逻辑删除词书（连带单词逻辑删除） |
| `GET /api/admin/monitor/ai-stats` | AI 调用次数/成功率/降级次数 + 降级事件列表 |

### 3.3 微信登录（已决策：真实调用）

- 组长提供 AppID / Secret，**只做真实 `code2Session` 调用，不加 mock 开关**。
- 学生端主入口联调依赖 AppID；在拿到之前，学生端可用账号密码登录（契约 §1.2 保留）走通链路。
- `user.openid` 为空时 `password_hash` 必须有值，`username` 唯一索引需允许 NULL（`UNIQUE` 允许多个 NULL，MySQL 语义正确）。

### 3.4 默认词书种子数据（决策）

三份 JSONL 共 22761 词，`saveBatch` 分批写入。种子导入以**管理端导入接口的同一套解析逻辑**实现（复用 `WordImportService`），避免两套映射代码漂移。

---

## 4. 规范文件路径索引（供 implement/check 子代理注入）

| 路径 | 用途 |
|---|---|
| `.trellis/spec/backend/index.md` | 后端规范入口 |
| `.trellis/spec/backend/directory-structure.md` | `backend/` + `ai-service/` 目录布局与命名 |
| `.trellis/spec/backend/database-guidelines.md` | MyBatis-Plus 查询模式、SQL 迁移脚本规范 |
| `.trellis/spec/backend/error-handling.md` | Result 包装、错误码分段、AI 降级路径 |
| `.trellis/spec/backend/quality-guidelines.md` | 禁用/必须模式、测试要求、评审清单 |
| `.trellis/spec/backend/logging-guidelines.md` | 日志级别、格式、敏感信息红线 |
| `.trellis/spec/guides/cross-layer-thinking-guide.md` | 跨层改动思考（契约/DTO 同步） |
| `docs/接口详细设计.md` | **字段级冻结契约**，唯一事实源 |
| `docs/需求清单-功能与规则.md` | 行为规则、边界、空态 |

---

## 5. 未解风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 无 AI key（DeepSeek） | ai-service 无法真实调用；backend 降级路径可测但成功路径不能端到端验证 | backend 的降级路径必须**先于**成功路径可测；ai-service 用 stub/假 key 跑单测 |
| 无微信 AppID | `wx-login` 无法端到端验证 | 契约 §1.2 账号密码登录作为等价链路；wx-login 用单元测试 + 手动验签逻辑覆盖 |
| `root` 密码属敏感信息 | 泄露风险 | 仅写入本地 `.env` / IDE 运行配置，`.gitignore` 排除，仓库内只留占位符 |
| 契约缺口 3.1/3.2 未回写文档则漂移 | 后续无法作为唯一事实源 | backend 子任务**先改文档再改代码**，纳入验收标准 |
