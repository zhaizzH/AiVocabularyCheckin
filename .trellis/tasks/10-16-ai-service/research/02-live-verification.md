# 真实路径验证证据（2026-10-17）

> 对应 `implement.md` 阶段 7 / 8 的验收动作。**不含 key、不含完整提示词、不含用户隐私数据。**
> 可复现命令见文末「复现方式」。

---

## 1. 环境

| 项 | 值 |
|---|---|
| ai-service | `127.0.0.1:8000`，`uvicorn app.main:app` |
| backend | `127.0.0.1:8080`，Spring Boot 3.5.6 / Java 21.0.11，profile `local` |
| 模型 | `deepseek-flash`（`deepseek-chat` / `deepseek-reasoner` 已于 2026-07-24 退役） |
| key 来源 | `ai-service/.env`（gitignored）；接口只报 `llmConfigured:true`，不回显值 |
| 依赖 | 隔离 venv，`langchain 1.3.18` / `langchain-openai 1.7.2` / `langgraph 1.2.16` |

`GET /health` → `{"status":"ok","llmConfigured":true}`

---

## 2. 真实调用：契约字段与耗时

`pytest tests/test_live_deepseek.py -q -s`（`DEEPSEEK_LIVE_TEST=1`）→ **4 passed**。

| 接口 | 实测耗时 | 自控 deadline | backend 超时 | 余量结论 |
|---|---|---|---|---|
| `/ai/v1/example` | **1048 ms** | 12 000 ms | 15 000 ms | 充裕（约 11.5%） |
| `/ai/v1/explain` | **3453 ms** | 25 000 ms | 30 000 ms | 充裕（约 14%） |
| `/ai/v1/report` | **2433 ms** | 50 000 ms | 60 000 ms | 充裕（约 5%，两次 LLM 调用） |

> 多轮实测区间（会话记录）：example 1048–1322 ms、explain 2918–3698 ms、
> report 2354–3077 ms。三个接口都**远低于**自控 deadline，15s 的 example 预算不紧张。

### 2.1 `/ai/v1/example` 真实响应

请求：`{"word":"abandon","translation":"v. 放弃；抛弃","context":"CET4"}`

```json
{
  "example": "The family had to abandon their home when the river flooded.",
  "exampleTranslation": "河水泛滥时，这家人不得不弃家而逃。",
  "mnemonic": "a + band + on：一群人（band）在（on）上面被迫散开——放弃；注意与 abundant（丰富的）区分：abandon 中间是 ban（禁止），禁止了就只能放弃。"
}
```

字段集合 `{example, exampleTranslation, mnemonic}` 与契约 §9.1 逐字一致，全部非空、中文正文。

### 2.2 `/ai/v1/explain` 真实响应

请求：`{"word":"abandon","translation":"v. 放弃；抛弃","wrongContext":"该词在复习中答错，累计错误 3 次"}`

```json
{
  "explanation": "abandon 的易错点主要在形近词和词性/搭配上。形近词 abundant（丰富的）与它只差两个字母：abandon 是 a-b-a-n-d-o-n，abundant 是 a-b-u-n-d-a-n-t，快速扫词时容易混淆。另外 abandon 常与表示「人、计划、地方、习惯」的宾语搭配（abandon a plan / abandon a child / abandon the ship），而不少学生受中文「放弃」影响，会误把它当不及物动词用（如直接说 I abandon，缺宾语），或与 give up、quit、desert、discard 混成一团：give up 偏「停止做某事」，quit 偏「辞去/戒掉」，desert 强调「违背责任地抛弃」，而 abandon 强调「彻底丢下、不再管」。词根线索：a-（去）+ bandon（古法语「控制、管辖」）→ 交出控制权 → 放弃、抛弃。",
  "advice": "1）把 abandon / abundant 并排各写两遍，并各造一句锚定：abandon a plan（放弃计划）、abundant evidence（大量证据），以后看到 a-b-a 开头就回忆「放弃」，a-b-u 开头回忆「丰富」。2）做一张四词对比卡：abandon（彻底丢下）、give up（停止做）、quit（辞去/戒掉）、desert（失责抛弃），每词配一个最短搭配（abandon ship / give up smoking / quit a job / desert a family），答题时按「宾语是什么」来选。3）每天用 abandon 口头造一个带宾语的句子并说出中文，强化它必须带宾语这一点。"
}
```

**关键验证（prd 验收 C 第 1 条）**：输入是 backend 真实构造的
`"该词在复习中答错，累计错误 3 次"`，**不含「用户误选了哪个选项」**。
模型没有编造误选项，而是围绕形近词、词性搭配、词根线索讲解 ✔

### 2.3 `/ai/v1/report` 真实响应

请求：`{"studyStats":{"studyDays":12,"totalLearned":346,"checkInRate":85,"topMistakeWords":["abandon","ambiguous","benefit"],"masteryRate":58}}`

```json
{
  "overview": "你已坚持学习 12 天，累计学习 346 个单词，近两周打卡率 85%，掌握度 58%。整体学习节奏稳定，学习量已有一定积累，但错题中仍有 4 成多尚未掌握。",
  "weakness": "掌握度 58% 说明已掌握的错题仅占错题总数的一半多一点，错题中仍有约 4 成多未掌握；高频错误词集中在 abandon、ambiguous、benefit，反复出错说明这三个词尚未真正记牢。",
  "suggestion": "本周优先专攻 abandon、ambiguous、benefit 三个高频错误词：逐个查词典确认词义与常见搭配，各造 3 个自用例句并朗读，隔天用闪卡自测，连续两次全对后再移出错题本。"
}
```

- `suggestion` 确实**引用了 `weakness` 的三个具体错词**（不是通用鸡汤）——这正是
  「analyze → compose 两步」要达到的效果。
- `checkInRate` 被正确表述为「近两周打卡率」，没有被误读成全周期（提示词约束生效）。
- `masteryRate` 被正确理解为「已掌握错题/错题总数」，没有被误读成「已学会单词比例」。

---

## 3. 快速路径（0 次 LLM）

请求：`topMistakeWords=[]` 且 `masteryRate=100`

`test_live_report_fast_path_still_contract_shaped` → **passed**：三段齐全、非空。

文案固定为「当前统计中暂无明显薄弱错题」，**不含**「已掌握全部学习内容」
（`test_report.py::test_report_fast_path_calls_llm_zero_times` 有断言）。

---

## 4. 失败路径（非 2xx + 稳定 detail）

| 场景 | 实测 | 说明 |
|---|---|---|
| 错误 key（`sk-invalid-key-for-negative-test`） | **HTTP 502** `ai_service_llm_failed`，0.3s | 非 2xx + 稳定 detail → backend 判 5002 ✔ |
| 缺 key（`.env` 无 `DEEPSEEK_API_KEY`） | **HTTP 503** `ai_service_llm_not_configured` | 服务仍能启动，`llmConfigured:false` ✔ |
| 客户端断开（`CancelledError`） | **原样上抛**，不转成 HTTP 状态码 | 2026-10-17 复审修复；回归用例 `test_cancellation_is_not_translated_into_http_error` ✔ |
| 单次尝试超时（`openai.APITimeoutError`） | **HTTP 504** `ai_service_timeout` | 2026-10-17 复审修复；回归用例 `test_llm_attempt_timeout_maps_to_504` ✔ |

**绝不**出现 `200 + 半截内容`。

---

## 5. 与 backend 的端到端（阶段 8）

| 步骤 | 结果 |
|---|---|
| `POST /api/client/ai/example` | `code:"0"`，`degraded:false`，`example` 为模型生成文本 |
| `POST /api/client/ai/explain` | `code:"0"`，`degraded:false` |
| `POST /api/client/ai/report` | `code:"0"`，`degraded:false`，三段非空（需 ≥3 天打卡，`studyDays<3` 时 backend 直接 5003 不发 HTTP） |
| **反证**：停掉 ai-service 重跑 | `example` → `degraded:true` + 回退词库例句；`explain` / `report` → `5002` |

反证这一步证明**降级决策确实在 backend**，而不是 ai-service 在做隐式降级。

---

## 6. 日志与红线扫描

- 日志扫描：**零** key 泄漏、零请求体泄漏、零 `wrongContext` 泄漏。
- 无 `print()`；`app/` 无 DB 相关 import；`requirements.txt` 无 DB 包；
  venv 内 `SQLAlchemy` **NOT INSTALLED**。

---

## 7. 复现方式

```bash
cd ai-service
# ① 起服务（key 已在 .env）
./.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000

# ② 离线全量（不访问外网）
./.venv/Scripts/python.exe -m pytest -q

# ③ 真实路径（默认 skip，需显式开启）
DEEPSEEK_LIVE_TEST=1 ./.venv/Scripts/python.exe -m pytest tests/test_live_deepseek.py -q -s

# ④ 契约字段 + 耗时检查（独立脚本）
./.venv/Scripts/python.exe scripts/verify_live.py
```

> ⚠️ `backend/scripts/g5-smoke.py` **刻意要求 8000 端口不可达**，
> 跑它与跑本文的真实联调互斥：必须先停 ai-service。
