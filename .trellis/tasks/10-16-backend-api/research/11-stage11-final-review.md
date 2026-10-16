# 阶段 11 收尾与 G7 闸口记录

> 目的：把 backend-api 子任务收成「可交付」——文档、脚本、一键自测、验收清单齐全，
> 并通过 G7 闸口。本文件记录**改了什么、为什么改、怎么验**。

---

## 1. 本阶段范围

| # | 产出 | 状态 |
|---|---|---|
| P1 | `application.yml` 生产日志默认收紧 | ✅ |
| P2 | `backend/README.md` 按最终交付状态重写 | ✅ |
| P3 | `backend/scripts/smoke-test.sh` 一键自测 + 根目录转发入口 | ✅ |
| P4 | 5 个冒烟脚本支持 `BASE`/DB 环境变量（可指向任意实例） | ✅ |
| P5 | 契约补记 AI 供应商 DeepSeek（S3 §3.5 + §1.6；接口设计 §9 + §10） | ✅ |
| P6 | `prd.md` 21 条验收标准逐条自查打勾 | ✅ |
| P7 | `implement.md` 阶段 11 条目勾选 + 实施记录 | ✅ |
| P8 | 全量重建 + 一键自测 + 红线复核 | ✅ |
| P9 | `clean-smoke-data.py` 测试数据清理脚本（发现并修复测试用户累积） | ✅ |

---

## 2. P1 生产日志默认收紧（G5 遗留）

**问题**：G5 敏感信息自查时发现，`application.yml` 默认
`logging.level.com.vocab.mapper: DEBUG` 会让 MyBatis 把 `user` 表的
`password_hash`（BCrypt 哈希）逐条打进日志（实测 grep 命中）。

**修法**：默认改 `INFO`；本地要看 SQL 时在 `application-local.yml` 单独设 DEBUG
（模板 `application-local.yml.example` 已含 `logging.level.com.vocab: DEBUG`）。

```yaml
logging:
  level:
    root: INFO
    com.vocab: DEBUG
    # 生产默认 INFO：DEBUG 会打印 user.password_hash（BCrypt 哈希）到日志。
    # 本地联调需看 SQL 时，在 application-local.yml 单独覆盖为 DEBUG。
    com.vocab.mapper: INFO
```

**为什么是「默认收紧」而不是「删掉」**：DEBUG 对排查 SQL 问题（尤其分页/聚合）
是刚需，删掉会让后续维护变难；改成默认关闭 + 本地显式开启，是安全与可维护的折中。

---

## 3. P3 一键自测脚本（本次最重要产出）

`backend/scripts/smoke-test.sh` 一次跑完：

1. 检测 `BASE` 上是否已有服务 → 有则复用，无则用 `target/*.jar`
   （不存在先 `mvn -o -q clean package -DskipTests`）以 `local` profile 拉起；
2. **前置断言**：`127.0.0.1:8000` 必须**不可达**。AI 降级是一等命题，
   ai-service 起着时 `g5-smoke.py` 的降级断言会失去意义 → 脚本直接拒绝并提示先停掉。
3. 依次跑 5 个冒烟脚本（子进程注入 `DBPASS`/`DB_*`/`BASE`）；
4. `mvn -o test`；
5. 汇总 PASS/FAIL + 服务日志 ERROR 计数，任一 FAIL → `exit 1`。

### 3.1 踩坑一：GBK 终端下 `grep -c` 数错 PASS

第一版用 `grep -acE '^\s*PASS' "$out"` 统计，结果把 `stage10` 的 46 个数成 47。

原因：脚本自己的汇总行是 `== 汇总46 PASS / 0 FAIL ==`，其中 `PASS` 前面
虽不是行首，但 g4 系列打印的是 `PASS: 26    FAIL: 0`——**`PASS:` 前缀行被计入**；
并且 Windows GBK 终端下 grep 对中文 UTF-8 输出的行切分/编码判定不稳定。

**修法**：改为**用 python 解析输出**（去 ANSI 色码、按行 strip 后前缀计数、
**显式排除 `PASS:`/`FAIL:` 汇总行**），并顺手把 `mvn` 的 `Tests run` 抓取与
日志 ERROR 计数一起换成 python。修后：26/18/24/29/46 与各脚本自报数字**完全一致**。

> 教训：**不要用 grep 统计非 ASCII 输出的行数**；统计口径要在脚本里写死并可自证。

### 3.2 踩坑二：U+2212 减号在 GBK 终端崩溃

`stage10-admin-smoke.py` 含一个 U+2212（数学减号 `−`）
→ Windows GBK 控制台 `UnicodeEncodeError`。已替换为 ASCII `-`。

### 3.3 根目录转发入口

`scripts/smoke-test.sh` 只做一件事：转发到 `backend/scripts/smoke-test.sh`，
让「在仓库根敲一条命令」即可自测。

---

## 4. P4 冒烟脚本可移植性

原来 4 个脚本把 `BASE` 和 MySQL 连接硬编码成 `127.0.0.1:8080` / `root@127.0.0.1/ai_vocab`。

改动（**只改连接参数来源，不动任何断言逻辑**）：

| 脚本 | `BASE` | DB |
|---|---|---|
| `g4-smoke.py` | `argv[1]` → `BASE` env → 默认 | 不用 DB |
| `g4-crossday.py` | `argv[1]` → `BASE` env → 默认 | `DB_HOST/DB_PORT/DB_USERNAME/DB_NAME`（默认本机） |
| `g5-smoke.py` | `argv[1]` → `BASE` env → 默认 | 不用 DB |
| `stage9-stats-smoke.py` | `argv[1]` → `BASE` env → 默认 | 不用 DB |
| `stage10-admin-smoke.py` | `argv[1]` → `BASE` env → 默认 | 同上 |

与「红线：脚本只创建/清理自己前缀数据」不冲突——**改的只是「连到哪」，
不是「动谁的数据」**，前缀约束逐字未变。

---

## 5. P5 契约补记（AI 供应商）

「先改契约文档、再写代码」的闸口 G6 已在阶段 10 前完成。本阶段只做
**供应商登记**（不影响任何字段契约）：

- `docs/S3-...-正文.md`：V1.3 → **V1.4**
  - §1.6 版本表补 V1.4 行
  - §3.5 AI 微服务层补「V1.4 实现补充——大模型供应商」段：
    DeepSeek（OpenAI 兼容 REST）／密钥走 `DEEPSEEK_API_KEY` 环境变量／
    用 `langchain-openai` 只换 `base_url`+`model` 即**不锁定供应商**／
    超时由调用方按 15s/30s/60s 控制
- `docs/接口详细设计.md`：V1.1 → **V1.2**
  - §9 内部 AI 接口补同一说明（**明确「对外契约不变」**）
  - §10 变更记录补 V1.2 行

> 每条替换都断言「恰好命中一次」，命中数不为 1 即 `sys.exit(1)`（防文本漂移）。

---

## 6. P6/P7 进度文件对齐

`prd.md` 21 条验收标准：**全部 `[x]`**（`grep '\[ \]'` 为 0 命中），
每条附「用什么证据验的」（脚本名 / 测试类 / 提交号）。
`implement.md` 阶段 11 条目 47–50 勾选 + 追加「阶段 11 实施记录」段。

---

## 7. G7 闸口证据（实测）

### 7.1 全量重建

```
mvn -o -q clean package            → BUILD SUCCESS
mvn -o test                        → Tests run: 326, Failures: 0, Errors: 0, Skipped: 0
```

### 7.2 一键自测（真实 jar + 真实 MySQL + 真实 HTTP）

```
DBPASS=*** bash backend/scripts/smoke-test.sh
  → exit 0，全部通过 ✅

  G4 核心闭环（§3-§5）       : 26 PASS / 0 FAIL
  G4 跨日规则（streak/跨天）  : 18 PASS / 0 FAIL
  G5 AI 中转与降级（§6）      : 24 PASS / 0 FAIL   ← ai-service 刻意未启动
  阶段 9 统计（§7）           : 29 PASS / 0 FAIL
  阶段 10 管理模块（§8）      : 46 PASS / 0 FAIL
  ------------------------------------------------------
  冒烟合计 143 用例，0 FAIL
  mvn -o test                : Tests run: 326, Failures: 0, Errors: 0
  服务日志 ERROR 行数         : 0
```

### 7.3 数据与红线

| 检查 | 结果 |
|---|---|
| `word_book` | 3 行（7508 / 5651 / 9602） |
| `word` | 22761 行 |
| `g6*` 冒烟残留用户 / `G6*` 词书 | **0** |
| 红线 `anime_tracker` | **20 表未变** |
| 敏感信息（明文密码 / AppSecret / 硬编码 URL） | grep 0 命中；`application-local.yml` 未被 git 跟踪 |

### 7.4 P9 发现：冒烟测试用户会累积（已修）

G7 前复核数据时发现 `user` 表积了 **49 个**测试账号（`g4_*`/`g4cd_*`/`g4n_*`/
`g5_*`/`g5t_*`/`stage9*` 等），以及**一个更早遗留的 `s61792147248`**
（阶段 5 早期用 `/tmp` 下临时冒烟脚本建的，前缀是 `s<时间戳>`，与现行脚本无关）。

原因：`stage10-admin-smoke.py` 会自清理（它能直连库精确删自己前缀）；
但 `g4*`/`g5*`/`stage9*` **不删用户**——因为删用户需要管理员接口，而这些脚本
刻意只用普通 JWT 会话。所以每跑一轮就多几个账号。

**修法**：新增 `backend/scripts/clean-smoke-data.py`：

- 按**前缀白名单**（`g4_`/`g4cd_`/`g4n_`/`g5_`/`g5t_`/`stage9`/`g6adm_`/`g6usr_`）
  找出测试用户，`LIKE` 模式里的 `_`/`%` 全部转义为字面量（否则 `_` 会当通配符误伤）；
- 连带删除这些用户的 `study_record`/`mistake_entry`/`word_progress`/`user_word_book`；
- 测试词书按名字前缀（`G6冒烟` 等）逻辑删除 + 单词物理删除；
- 末尾**复核种子数据不变**（未删除词书必须仍是 3 本、`word` 仍是 22761）；
- 支持 `--dry-run` 先预览待删对象。

清理后：`user`=0、`study_record`=0、`word_progress`=0、`mistake_entry`=0、
`user_word_book`=0，`word`=22761、`word_book`=3、红线 20 表未变。

> 设计取舍：**不把清理写成「自动化」**（不在 `smoke-test.sh` 末尾无条件删），
> 因为删数据必须让人看得见。脚本独立、可 `--dry-run`、且复核种子数据，更符合
> 「测试要可观测」的原则。

---

## 8. 遗留与后续

1. **`data/vocab-source/*.jsonl`（约 20MB）不入库**——只 `LICENSE` 入库。
   重新拉取见 `scripts/fetch-vocab.sh`。
2. **AI 成功路径未验**（无 DeepSeek key）：`ai-service` 只到「代码就绪 + stub 单测」。
   key 到位后需补「真实调用成功」的证据（属 `ai-service` 子任务）。
3. **小程序端 / 管理端前端**为独立子任务，不在本任务范围。
4. 工作区另有 23 个 `docs/logo/*.png` 与 `docs/小程序注册信息-...md` 的**删除**
   非本任务引入，全程刻意不纳入本任务任何提交。
5. `ai-service` 的 `DEEPSEEK_API_KEY` 未提供 → S3 §3.5 只登记供应商，
   真实生成能力待 key 到位后由 `ai-service` 子任务补验。
