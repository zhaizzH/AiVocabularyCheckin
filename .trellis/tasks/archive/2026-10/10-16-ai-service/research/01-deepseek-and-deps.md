# 研究：DeepSeek 供应商现状、依赖锁版与调用参数

> 探测时间：2026-10-17 · 目的：为 `10-16-ai-service` 的 `design.md` / `implement.md` 提供**可复现的**外部事实。
> 方法：官方 API 文档抓取 + PyPI 元数据实测 + 本机 import 实测。所有结论标注来源。

---

## 1. 依赖锁版（本机实测，非推测）

### 1.0 ⚠️ 版本基线已由用户指定：`langchain==1.3.18`（2026-10-17 用户指令）

全家族升至 1.x，实测版本矩阵（**隔离 venv 实测**，见 §1.2）：

| 包 | 采用版本 | 关系 |
|---|---|---|
| `langchain` | **1.3.18** | 用户指定 |
| `langchain-core` | 1.6.12 | 由 langchain 带入（要求 `>=1.6.0,<2.0.0`） |
| `langgraph` | 1.2.16 | **langchain 1.3.18 的必需依赖**（`>=1.2.11,<1.3.0`），非可选 |
| `langchain-openai` | 1.7.2 | 要求 `langchain-core>=1.6.10,<2.0.0` + `openai>=2.45,<4` |
| `openai` | 3.33.0 | 由 langchain-openai 带入 |
| `pydantic` | 2.9.2 | 与 langchain-core 要求 `>=2.7.4,<3` 兼容 |

### 1.1 🔴 重要更正：1.x 的 `langchain` **不再依赖 SQLAlchemy**

先前结论（基于 0.3.x）已**失效**。实测两个版本的元数据对比：

| 版本 | 必需依赖 | 含 SQLAlchemy? |
|---|---|---|
| `langchain==0.3.7`（旧全局环境） | `SQLAlchemy>=1.4,<3`、`aiohttp`、`numpy`、`PyYAML`、`requests`、`tenacity`、`langsmith` | ✅ 有 → 会违反红线 |
| `langchain==1.3.18`（**本次采用**） | `langchain-core<2,>=1.6.0`、`langgraph<1.3,>=1.2.11`、`pydantic<3,>=2.7.4` | ❌ **无** |

`langchain 1.3.18` 的 wheel METADATA 原文：

```
Requires-Dist: langchain-core<2.0.0,>=1.6.0
Requires-Dist: langgraph<1.3.0,>=1.2.11
Requires-Dist: pydantic<3.0.0,>=2.7.4
```

**完整闭包实测**（`pip install --dry-run --ignore-installed --report`）：
`langchain==1.3.18` 共解析出 **39 个包**，逐名筛查
`sqlalchemy|mysql|psycopg|sqlite|aiosqlite` → **DB-RELATED: NONE**。

**结论**：可以**正常安装 `langchain` 元包**，红线依然成立。
原先「只装 core + openai、避开元包」的规避策略**不再需要**，且既然用户明确要求
使用 `langchain` 与 `langgraph`，就按标准方式安装三者。

> 红线判据**不变**：不是「全局环境有没有 SQLAlchemy」，而是
> ①`ai-service` 的**依赖闭包**里有没有（实测无）；②代码里有没有引用（AST 测试机械保证）。

### 1.2 隔离 venv 实测记录（2026-10-17）

为避免顶掉本机全局的 `langchain 0.3.7`（其他项目可能在用），
验证在临时 venv（`python -m venv`，Python 3.12.7）中进行：

```bash
python -m venv /tmp/aivenv
/tmp/aivenv/Scripts/python.exe -m pip install \
  "langchain==1.3.18" "langchain-openai==1.7.2" \
  "fastapi==0.115.5" "uvicorn==0.32.0" "pydantic==2.9.2" \
  "pydantic-settings==2.6.1" "pytest==8.3.3" "pytest-asyncio==0.24.0"
# 结果：EXIT=0，无冲突
```

| 实测项 | 结果 |
|---|---|
| `importlib.metadata.version('SQLAlchemy')` | **NOT INSTALLED**（`ModuleNotFoundError`）✅ 红线成立 |
| `langchain` 元数据 db 相关依赖 | none ✅ |
| `langchain-core` 元数据 db 相关依赖 | none ✅ |
| `langchain-openai` 元数据 db 相关依赖 | none ✅ |
| `langgraph` 元数据 db 相关依赖 | none ✅ |
| `langgraph-checkpoint` / `-prebuilt` / `-sdk` / `langsmith` 的 db 依赖 | none（亦无 `sqlite`/`postgres` extra 被启用）✅ |
| `from langchain_core.prompts import ChatPromptTemplate` | ✅ |
| `from langchain_core.output_parsers import StrOutputParser` | ✅ |
| `from langchain_openai import ChatOpenAI` | ✅ |
| `from langchain.chat_models import init_chat_model` | ✅ |
| `from langgraph.graph import StateGraph, END` | ✅ |
| `fastapi.testclient.TestClient` GET 200 + `/openapi.json` 可用 | ✅ |

**venv 定位说明**：仅作为**验证沙箱**。真实交付时 `requirements.txt` 锁版本，
用户可用任意 venv 复现（`.venv/` 已在 `.gitignore` 中，见 §1.4）。

### 1.3 `langgraph` 的使用决策（用户明确要求使用）

langgraph 已成为 `langchain 1.3.18` 的**必需依赖**，因此「用不用」在依赖层面
已不是选择题；真正要定的是**用在哪、是否值得**。

**决策：仅用于「学习报告」的多步编排（`report`），example / explain 保持纯 LCEL 单链。**

理由：

1. **语义匹配**：报告是**唯一**真正多步、且有中间产物的功能
   （digest → 条件路由 → analyze → compose；符合条件时 digest 直接结束，不调用 LLM）。langgraph 的 `StateGraph` + 显式状态字典
   恰好表达「每步只增改自己那份状态」，比手写中间变量更可读、可测。
2. **不滥用**：example/explain 是「一次 LLM 调用 + 一次解析」，
   用 `StateGraph` 包一层是纯粹的仪式性复杂度（节点 1 个、边 1 条）。
   S3 §3.5 要求的是「多步 Chain」，不是「所有东西都上图」。条件边的价值在于跳过不需要的模型调用，而不只是为了使用图 API。 
3. **可测性**：`StateGraph.compile()` 后返回的 `Pydantic`/`Runnable` 对象
   有 `invoke` / `ainvoke`，**可被单测直接调用**，也能被 FakeLLM 替换节点函数（§2.6 已实测）。
4. **零成本**：langgraph 无论如何都会被安装，用它不增加依赖体积。

**基础线性图 API 实测**（仅验证 StateGraph 节点注册、编译及异步调用可用；实际产品图的条件路由见 `design.md §6.3`）：

```python
from typing import TypedDict
from langgraph.graph import StateGraph, END

class ReportState(TypedDict, total=False):
    stats: dict              # 输入
    analysis: str            # step1 产出
    report: dict             # step2 产出

g = StateGraph(ReportState)
g.add_node("digest", digest)      # 纯 Python，不调 LLM
g.add_node("analyze", analyze)    # LLM #1
g.add_node("compose", compose)    # LLM #2
g.set_entry_point("digest")
g.add_edge("digest", "analyze")
g.add_edge("analyze", "compose")
g.add_edge("compose", END)
app = g.compile()
await app.ainvoke({"stats": {...}})  # → {'stats':..., 'analysis':..., 'report':...}
```

基础图输出曾以 `invoke` 与 `ainvoke` 分别确认可用；产品实现必须使用 `ainvoke`，以便请求级 asyncio deadline 能取消进行中的模型调用。条件路由的行为与 0-LLM 快速路径还须在锁定版本上增加独立测试，不能把这段线性图实测当作条件图验收证据。 

### 1.4 `.env` 忽略规则（实测确认，无需改动 `.gitignore`）

`.gitignore` 第 2/3/4 行：

```
.env
.env.*
!.env.example
```

实测（Windows Git Bash）：

| 命令 | 结果 |
|---|---|
| `git check-ignore -q ai-service/.env` | exit **0** → 被忽略 ✅ |
| `git check-ignore -q ai-service/.env.example` | exit **1** → 未被忽略 ✅（可入库） |
| `git status --porcelain ai-service/`（仅有 `.env` 与 `.env.example` 时） | `?? ai-service/`（未逐文件列举，说明 `.env` 未被跟踪）✅ |

### 1.5 `langchain-openai` 与 DeepSeek 的兼容性（1.7.2 复测）

参数面与 0.2.6 **一致**，迁移无 API 破坏（本机实测）：

```python
llm = ChatOpenAI(model="deepseek-flash", base_url="https://api.deepseek.com",
                 api_key="sk-not-real", timeout=12, max_retries=2,
                 max_tokens=512, extra_body={"thinking":{"type":"disabled"}})
# model_name='deepseek-flash'  base='https://api.deepseek.com'
# request_timeout=12.0  max_retries=2  max_tokens=512
# extra_body={'thinking': {'type': 'disabled'}}
# has invoke=True  has ainvoke=True
```

**注意**：构造函数的签名参数（`base_url` / `timeout` / `max_tokens` / `extra_body`）
不出现在 `inspect.signature(ChatOpenAI.__init__)` 里（由 pydantic 字段机制接管），
**不能**用签名探测判断支持性 —— 以上均已通过**实际构造**验证。

**结论**：`ChatOpenAI` 可直接指向 DeepSeek；非标准参数经 `extra_body=` 透传。
官方另有 `langchain-deepseek` 包（1.1.1，依赖 `langchain-openai>=1.3.1`），
但**本任务不引入**：它的价值是封装 deepseek 专有参数，而我们只需 `ChatOpenAI`
+ `extra_body` 透传，引入反而多一层版本耦合。

### 1.6 `openai` 3.x 的异常类（错误映射用，实测存在）

```
openai.APITimeoutError ✅   openai.APIConnectionError ✅
openai.APIStatusError ✅    openai.AuthenticationError ✅
openai.BadRequestError ✅   openai.RateLimitError ✅
openai.InternalServerError ✅
openai.APIConnectionTimeoutError ❌（不存在；不要写这个类名）
```

---

## 2. DeepSeek 供应商现状（**已变化，与 S3/契约文档的隐含假设不同**）

### 2.1 模型名：`deepseek-chat` / `deepseek-reasoner` **已于 2026-07-24 退役**

来源：<https://api-docs.deepseek.com/updates/> 、
<https://www.developersdigest.tech/blog/deepseek-chat-to-v4-migration-guide>

当前官方文档（<https://api-docs.deepseek.com/>）只列两个模型：

| model id | 状态 | 说明 |
|---|---|---|
| `deepseek-flash` | **当前推荐**（V4.1-Flash，2026-09-10 发布） | 「recommended id for new integrations」；多模态；成本最低 |
| `deepseek-v4-pro` | **正在淘汰**（2026-09-14 起改路由到 V4.1-Flash） | 更贵 |

> ⚠️ Google/搜索引擎里大量「`langchain_openai` + `deepseek-chat`」的教程**已过期**。
> 若照抄旧教程用 `deepseek-chat`，运行时会 401/400。

**决策**：默认 `model=deepseek-flash`，且**做成环境变量可覆盖**（`DEEPSEEK_MODEL`），
以符合 S3 §3.5「只换 base_url/model 即可切换供应商」的可替换性承诺。

### 2.2 base_url

- OpenAI 兼容：`https://api.deepseek.com`（**不带 `/v1` 亦可**，官方 SDK 示例即用此）
- 旧教程常写 `https://api.deepseek.com/v1`——同样有效（OpenAI SDK 会补 `/chat/completions`）。
- **决策**：默认 `DEEPSEEK_BASE_URL=https://api.deepseek.com`（官方当前写法），可 env 覆盖。

### 2.3 🔴 陷阱：思考模式（thinking）**默认开启**，且与 JSON 输出冲突

来源：<https://api-docs.deepseek.com/guides/thinking_mode/> 、<https://api-docs.deepseek.com/guides/json_mode/>

实测文档原文要点：

1. 「**Thinking mode is enabled by default, with the default effort being `high`**」
   —— 即不写任何参数时，模型**默认开启思考**。
2. 「**Thinking mode does not support the `temperature`, `presence_penalty`, or
   `frequency_penalty` parameters**」——设了不报错但**静默无效**。
3. 关闭方式（OpenAI 格式）：`extra_body={"thinking": {"type": "disabled"}}`。
4. **已知冲突**：社区 issue 报告 `response_format=json_object` + thinking 会出现
   **`content` 为空、只有 `reasoning_content`** 的响应
   （见 <https://github.com/green-dalii/obsidian-llm-wiki/issues/275> 、
   <https://github.com/vllm-project/vllm/issues/41132>）。
5. JSON Output 的硬性要求：`response_format={'type':'json_object'}` **且**
   提示词里**必须出现 "json" 字样** **且**给出 JSON 示例，否则模型可能
   一直输出空白直到 `max_tokens` 耗尽（表现为「请求卡死」）。

**决策（三条，都会写进 design.md）**：
- 三个功能**统一关闭思考**：`extra_body={"thinking": {"type": "disabled"}}`。
  理由：①我们要的是一次性结构化 JSON，不需要 CoT；②思考会显著增加延迟，
  而 backend 侧超时预算只有 **15s/30s/60s**，思考模式很容易顶穿 15s 的例句接口；
  ③规避上面第 4 条的「空 content」已知缺陷。**代价**：复杂讲解的推理深度略降，
  但本项目讲解/例句/报告都是短文本生成，收益远大于损失。
- 统一走**结构化 JSON 输出**（开 `json_object` + 提示词含 "json" + 示例 + `max_tokens` 上限）。
- 不提供开启 thinking 的配置开关：所有请求都显式传 `thinking: disabled`；若旧配置中 `DEEPSEEK_THINKING_ENABLED=true`，实现应拒绝启动，避免误认为该模式受支持。

### 2.4 langchain-openai 与 DeepSeek 的兼容性注意

来源：<https://docs.langchain.com/oss/python/integrations/chat/openai>

- `ChatOpenAI` 官方声明「targets official OpenAI API specifications only」，
  第三方扩展字段（如 `reasoning_content`）**不会被提取或保留**。
- 本任务只消费 `content`（结构化 JSON 正文），**不依赖 `reasoning_content`** →
  该限制**不影响本任务**。这也正是「关掉思考」的又一个理由：
  开着思考而 langchain 丢弃 `reasoning_content`，等于白付 token 又拿不到价值。
- `ChatOpenAI` 可直接 `model=` / `base_url=` / `api_key=` 指向 DeepSeek；
  非标准参数经 `extra_body=` 透传（1.7.2 已实测，见 §1.5）。

### 2.5 本机实测的可用性证据（汇总）

| 项 | 结果 |
|---|---|
| `api.deepseek.com` 可达性 | 401（缺 key）→ 网络通、路径正确（父任务 research 已实测） |
| 隔离 venv 安装 `langchain 1.3.18` + `langchain-openai 1.7.2` + FastAPI 栈 | ✅ EXIT=0，无冲突 |
| 安装后 `SQLAlchemy` 是否存在 | ❌ NOT INSTALLED（红线成立） |
| `langchain` / `langchain-core` / `langchain-openai` / `langgraph` 是否声明 db 依赖 | ❌ 均无 |
| `from langchain_openai import ChatOpenAI` | ✅ |
| `from langchain_core.prompts/output_parsers/runnables/messages import ...` | ✅ |
| `from langgraph.graph import StateGraph, END`（含 invoke/ainvoke 实跑） | ✅ |
| `ChatOpenAI(...)` 构造后 `model_name` / `base_url` / `request_timeout` / `max_retries` / `max_tokens` / `extra_body` 均正确落地 | ✅ 逐个实测 |
| `fastapi.testclient.TestClient` 可用 | ✅ GET 200 + `/openapi.json` |
| LCEL 管道 `prompt \| fake \| StrOutputParser()`（sync + async） | ✅ 实测 |

---

## 3. 契约与 backend 侧的既有事实（读码所得，非推测）

### 3.1 backend 如何消费 ai-service（`AiServiceClient.java`）

- 三个路径：`/ai/v1/example`、`/ai/v1/explain`、`/ai/v1/report`；超时 15s/30s/60s。
- 连接超时取 `min(5000, timeoutMs)`。
- `RestTemplate.postForObject(...) == null` → 视为失败（`AiCallException`）。
- 任何 `RestClientException`（含 4xx/5xx、连接被拒）→ `AiCallException`，
  **靠异常链里有没有 `SocketTimeoutException` / `java.net.http.HttpTimeoutException`
  来区分 5001/5002**。

**推论（会写进验收）**：ai-service 只要返回**非 2xx** 或**非 JSON 可反序列化体**，
backend 一律映射 5002；返回 200 + 合法 JSON 即算成功。

### 3.2 backend 的 DTO 字段名（**必须逐字对齐**）

```
POST /ai/v1/example  ←  {word, translation, context}
                        → {example, exampleTranslation, mnemonic}
POST /ai/v1/explain  ←  {word, translation, wrongContext}
                        → {explanation, advice}
POST /ai/v1/report   ←  {studyStats:{studyDays,totalLearned,checkInRate,topMistakeWords,masteryRate}}
                        → {overview, weakness, suggestion}
```

`studyStats.topMistakeWords` 是 **`List<String>`**；其余数字为 **int**（Java `int`，
非 Integer）→ ai-service 侧必须能容忍「无错题时传空数组 `[]`」。

**关键**：backend 用 Jackson 反序列化响应，**未知字段会怎样？**
本项目 `AiServiceClient` 未配 `FAIL_ON_UNKNOWN_PROPERTIES=false`，
但 Spring Boot 默认已关闭该开关（`spring.jackson.deserialization.fail-on-unknown-properties=false`），
所以 ai-service **多返回字段是安全的**。不过为降低风险，实现仍**严格只返回契约字段**。

### 3.3 `context` 字段的取值

backend 传的是 `word_book.category`（实测由 `resolveCategory` 给出，如 `CET4`/`CET6`/`KAOYAN`），
**可能为空串**（词书缺失时）。ai-service 的提示词必须容忍空 `context`。

### 3.4 `wrongContext` 的真实语义（**与契约 §9.2 示例不同**）

backend 实测代码（`AiServiceImpl#buildWrongContext`）构造的是：

```
"该词在复习中答错，累计错误 3 次"     // 或 "该词在学习中答错，累计错误 N 次"
```

而**不是**契约 §9.2 示例里的 `"用户误选「举办」"`。原因见
`AiExplainCallRequest` 的类注释：本项目不记录选项数据。

**推论**：ai-service 的讲解提示词**不能假设 wrongContext 里有「用户选了哪个错误选项」**，
只能围绕「该词反复答错 + 次数」来生成。这是提示词设计的关键约束。

---

## 4. 与本任务相关的既有红线（摘自 `.trellis/spec/backend/`）

| 红线 | 来源 | 对本任务的具体约束 |
|---|---|---|
| 禁 print() | quality-guidelines | 用 `logging`，`app/core/` 统一配置 |
| 禁裸 `except:` | quality-guidelines | 用 `except Exception` |
| 禁可变默认参数 | quality-guidelines | 函数默认值不用 `[]`/`{}` |
| `requirements.txt` 必须锁版本 | quality-guidelines（明确点名） | `==` 精确锁 |
| 禁硬编码密钥 | quality-guidelines | `pydantic-settings` 读环境变量 |
| 每个路由类型化 Pydantic 模型，**不接受裸 dict** | quality-guidelines | 请求/响应都建模型 |
| 失败抛 `HTTPException`，detail **稳定字符串** | error-handling | 常量表，不放 `e.getMessage()` |
| 日志格式 `%(asctime)s %(levelname)s %(name)s %(message)s` | logging-guidelines | 在 `core/logging.py` 配一次 |
| 每次 AI 调用记录功能名/耗时/结果 | logging-guidelines | INFO 记录，但不记请求体敏感内容 |
| **禁连数据库**（架构一等约束） | directory-structure | 见 §1.3，连依赖都不带入 |

---

## 5. 已决策 / 待用户决策

### 5.1 已决策（有证据，不占用提问）

| # | 决策 | 依据 |
|---|---|---|
| 1 | 版本基线 `langchain==1.3.18` + `langgraph` 必需依赖 | 用户明确指令；闭包无数据库包实测 |
| 2 | 可以安装 `langchain` 元包（1.x 已移除 SQLAlchemy 依赖） | §1.1 元数据实测 |
| 3 | `langgraph` 仅用于 report 的多步编排 | §1.3 |
| 4 | 模型 `deepseek-flash`，`thinking` 默认 disabled | §2.1 / §2.3 |
| 5 | `base_url=https://api.deepseek.com` | §2.2 |
| 6 | 不引入 `langchain-deepseek` 包 | §1.5 |
| 7 | **真实 key 注入方式：写入 `ai-service/.env`**（用户已选） | 用户答复（2026-10-17）；`.env` 已 gitignore 实测 |
| 8 | **本轮包含真实端到端验收**（三接口真实调用 + backend 联调） | 用户确认 key 可用 |

### 5.2 仍待用户提供

| # | 需要什么 | 何时需要 |
|---|---|---|
| 1 | **DeepSeek API key 的实际值** | 执行 `implement.md` 阶段 7 时（届时向用户索取） |
| 2 | 确认是否允许在本机建 `ai-service/.venv`（如需隔离环境） | 启动实现前可选 |

（模型选择 `deepseek-flash`、思考模式 `disabled` 已按 §2.1/§2.3 的证据定为默认值，
均可用环境变量覆盖，不单独占用一次提问。）

---

## 6. 实现前复核（2026-10-17，针对更新后的 design/implement）

更新后的 `design.md` §5/§6.3/§9 提出了若干「必须用锁版实测确认」的要求。以下为实测结果。

### 6.1 🔴 `max_tokens` 会被改名成 `max_completion_tokens`（DeepSeek 不认）

`langchain-openai==1.7.2` 在组包时把 `max_tokens=` 改写为 **`max_completion_tokens`**
（OpenAI 新命名），实测请求体顶层键：

| 传参方式 | 实际发出的键 |
|---|---|
| `ChatOpenAI(max_tokens=512)` | `max_completion_tokens: 512` ❌ |
| `model_kwargs={"max_tokens":512}` | `max_completion_tokens: 512` ❌ |
| **`extra_body={"max_tokens":512}`** | **`max_tokens: 512`** ✅ |
| `ChatOpenAI(max_completion_tokens=512)` | `max_completion_tokens: 512` |

DeepSeek 的 Chat Completions 用 `max_tokens`。**结论：`max_tokens` 必须经 `extra_body` 传入**，
不能作为 `ChatOpenAI` 构造参数，否则线上会被供应商忽略（或报未知参数）。

最终请求体实测（`extra_body={"thinking":{"type":"disabled"},"max_tokens":512}` + `bind(response_format=...)`）：

```
顶层键: ['max_tokens', 'messages', 'model', 'response_format', 'stream', 'thinking']
response_format: {'type': 'json_object'}
thinking:        {'type': 'disabled'}
无 extra_body 包装层 ✅   无 temperature ✅
```

这同时验证了 design §5 要求的「最终 HTTP body 层面」两件事：
①`response_format` 在**顶层**；②`thinking` 在**顶层**（不是 `extra_body` 字面包装）。

### 6.2 `http_async_client` 是 1.7.2 的合法字段

`'http_async_client' in ChatOpenAI.model_fields` → **True**；传入后实例绑定成功，
可用 `httpx.MockTransport` 做到**零外网**的 HTTP 层断言（`tests/test_llm_request.py` 的可行基础）。

### 6.3 `asyncio.timeout` 确实能把取消传播进 Runnable

实测：`async with asyncio.timeout(0.3): await slow_runnable.ainvoke(...)`
→ 0.31s 抛 `TimeoutError`，**且 Runnable 内部 `asyncio.sleep` 收到 `CancelledError`**
（`cancelled` 计数为 1）。据此 design §9 的「取消传播 + compose 不得启动」可测。

### 6.4 langgraph 1.2.16 的条件路由与快速路径实测通过

`add_conditional_edges("digest", lambda s: s["route"], {"complete": END, "analyze": "analyze"})`：

| 输入 | 结果 | LLM 等价节点调用次数 |
|---|---|---|
| 快速路径 | `route=complete`，直接得到完整 report | analyze 0 / compose 0 |
| 常规路径 | `analysis` 与 `report` 均写入 state | analyze 1 / compose 1 |

### 6.5 `pytest-asyncio==0.24.0` 可安装

`import pytest_asyncio` → `0.24.0` 导入成功（已装入隔离 venv）。

### 6.6 `ChatOpenAI(timeout=)` 覆盖共享 client 的 timeout（实测）

用真实 TCP 服务器（接受连接后永不响应）测，避免 MockTransport 不实现超时的问题：

| 配置 | 实际超时时刻 |
|---|---|
| `ChatOpenAI(timeout=1.0)`，无共享 client | 1.12s → `OpenAITimeoutError` |
| `ChatOpenAI(timeout=1.0)` + `AsyncClient()`（默认 5s） | 1.02s → `OpenAITimeoutError` |
| `ChatOpenAI(timeout=99.0)` + `AsyncClient(timeout=1.0)` | **99.02s** → `OpenAITimeoutError` |
| `ChatOpenAI(timeout=1.0)` + `AsyncClient(timeout=60)` | 1.02s → `OpenAITimeoutError` |

**结论**：`ChatOpenAI(timeout=)` 是权威，会覆盖共享 client 的默认超时。
所以共享 `AsyncClient` 不设短默认值也不会误伤长调用；单次尝试上限完全由
`LLM_ATTEMPT_TIMEOUT_S` 决定。

> ⚠️ 方法学提醒：**不能用 `httpx.MockTransport` 验证超时**——MockTransport 的 handler
> 不参与 httpx 的超时机制，实测 `timeout=1.0` 配 3s 的 handler 会「正常完成」，
> 得出「timeout 被忽略」的错误结论。验证超时必须用真实 socket。

### 6.7 `ASGITransport` 不执行 lifespan（实测）

`fastapi.lifespan=` 形态下：

| 方式 | 观察到的 lifespan 事件 |
|---|---|
| `httpx.AsyncClient(transport=httpx.ASGITransport(app=app))` 发请求 | **`[]`** |
| `fastapi.testclient.TestClient(app)` 作上下文管理器 | `['startup', 'shutdown']` |
| 手动 `ctx = app.router.lifespan_context(app)` → `__aenter__` / `__aexit__` | ✅ 可控 |

手动方式实测可**连续进入两轮**（第二轮事件 `['startup', 'shutdown']` 完整复现），
满足「重复启动/关闭 lifespan」的测试用例，且**不需要**新增 `asgi-lifespan` 依赖。

注意：本机 `python -W error` 会因 `@app.on_event` 已弃用而报错，实现须用
`lifespan=` 上下文管理器形态，不用 `on_event`。

### 6.8 `pytest-asyncio` 未配置时会静默跳过全部 async 用例（实测）

隔离 venv 内 `pytest-asyncio 0.24.0` 已安装、但 `pytest.ini` 未设 `asyncio_mode`：

```
PytestUnhandledCoroutineWarning: async def functions are not natively supported and were skipped.
1 skipped, 1 warning in 0.03s        # exit code = 0
```

加上 `[pytest] asyncio_mode = auto` 后：

```
1 passed in 0.03s
```

**结论**：`pytest.ini`（含 `asyncio_mode = auto`）是交付物的一部分，
否则本任务全部用例被跳过而闸口「全绿」仍为真——最隐蔽的假绿来源。
