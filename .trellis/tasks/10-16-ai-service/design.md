# ai-service 技术设计

> 本文件承载**技术设计**（边界、契约、数据流、权衡、兼容性、回滚形状）。
> 需求与验收标准见 `prd.md`；执行顺序与验证命令见 `implement.md`；
> 外部事实与证据见 `research/01-deepseek-and-deps.md`。

---

## 1. 边界

### 本服务负责

- 三个生成接口的实现：例句生成、错题讲解、学习报告。
- 提示词工程：中文输出、结构化 JSON、可解析容错。
- 大模型调用编排（LangChain LCEL）+ 有限重试 + 超时自控。
- 失败时抛 `HTTPException`，`detail` 为**稳定字符串**。

### 本服务不负责（**明确不做**）

| 不做 | 理由 |
|---|---|
| 任何数据库连接/驱动/ORM | **架构一等红线**（S3 §3.1、spec/directory-structure.md） |
| 数据查询与统计计算 | 统计口径唯一事实源是 backend 的 SQL 聚合（`StatsMapper`） |
| 鉴权 | 契约 §9 未定义；内网调用 |
| 缓存 | 契约 §6.3 明确报告不缓存 |
| 写文件/会话状态 | 无状态部署要求 |
| 修改 backend 或 `docs/` 契约字段 | 本任务边界是「按已冻结契约把 ai-service 做对」 |

### 边界图（数据流）

```
小程序/管理端
   │  (wordId / mistakeId / 无体)
   ▼
backend  AiServiceImpl ── 查库取 word/translation/category/统计数字 ──┐
   │                                                                │
   │  POST /ai/v1/{example,explain,report}  (纯文本/数字，无 id)      │
   ▼                                                                │
ai-service ── 提示词组装 → ChatOpenAI(DeepSeek) → JSON 解析 ── 结构化字段 ┘
   │                         （本服务**不接触**任何数据库）
   ▼
backend 映射为对外契约（含 degraded 逻辑）→ 前端
```

**关键推论**：ai-service 的每个请求都**自带全部上下文**，因此可以独立单测——
不需要数据库、不需要 backend 在跑。这是本设计能「先于/并行于 backend」收尾的基础。

---

## 2. 契约（冻结，逐字对齐）

来源：`docs/接口详细设计.md` §9。**不得新增/改名任何字段。**

| # | 路径 | 请求 | 响应 | backend 超时 |
|---|---|---|---|---|
| 9.1 | `POST /ai/v1/example` | `{word, translation, context}` | `{example, exampleTranslation, mnemonic}` | 15s |
| 9.2 | `POST /ai/v1/explain` | `{word, translation, wrongContext}` | `{explanation, advice}` | 30s |
| 9.3 | `POST /ai/v1/report` | `{studyStats:{studyDays,totalLearned,checkInRate,topMistakeWords,masteryRate}}` | `{overview, weakness, suggestion}` | 60s |

### 2.1 backend 侧消费口径（读 `AiServiceClient.java` 所得，约束我们的输出）

1. **成功判据 = HTTP 2xx + 可被 Jackson 反序列化的 JSON**。
   → 必须**永远返回严格符合契约的 JSON**；解析失败时**不要**返回 200 + 半截文本。
2. 非 2xx、或响应体不可解析 → backend 记 `AiCallException(isTimeout=false)` → **5002**。
3. backend 的 5001 只由**它自己**的读超时产生（靠 `SocketTimeoutException` /
   `java.net.HttpTimeoutException` 判定），与我们的状态码无关。
4. `studyStats.topMistakeWords` 是 `List<String>`，**可能为空数组**（用户无错题）。
5. `context` 为词书 category（`CET4`/`CET6`/`KAOYAN`），**可能为空串**。
6. `wrongContext` 实测形如 `"该词在复习中答错，累计错误 3 次"`，
   **不含用户所选的错误选项** → 提示词不得假设能拿到它。

### 2.2 请求字段的宽松度（向后兼容策略）

| 字段 | 策略 | 理由 |
|---|---|---|
| 所有 string | **必填、非空白校验**（`min_length=1` + strip 后非空） | 契约保证 backend 只传非空 word/translation |
| `example.context` | **允许空串/缺省为 `""`** | backend `resolveCategory` 可能返回 `""` |
| `explain.wrongContext` | **允许空串** | 同上，容错优先 |
| `report.studyStats.topMistakeWords` | **允许 `[]`，默认 `[]`** | 无错题用户 |
| `report.studyStats` 各 int | **必填**，不做范围强校验（容 `0`） | backend 已保证 0–100 |

> 设计取舍：**宁可宽松接受、也不要因过度校验让 backend 拿到 422**——
> 因为 422 在 backend 侧同样变成「AI 服务异常」，而一次本可成功生成的请求被拒
> 是纯损失。校验只拦「确实无法生成」的输入（空白 word）。

---

## 3. 工程结构

```
ai-service/
├── app/
│   ├── __init__.py
│   ├── main.py                 # 应用工厂 create_app() + 路由注册 + lifespan
│   ├── core/
│   │   ├── __init__.py
│   │   ├── config.py           # pydantic-settings：环境变量唯一入口
│   │   ├── logging.py          # logging 统一配置（spec 指定格式）
│   │   ├── llm.py              # ChatOpenAI 工厂 + 无 key 时的显式失败
│   │   └── errors.py           # 稳定 detail 常量 + HTTPException 构造器
│   ├── schemas/
│   │   ├── __init__.py
│   │   ├── common.py           # 共用：StudyStats
│   │   ├── example.py          # ExampleRequest/ExampleResponse
│   │   ├── explain.py          # ExplainRequest/ExplainResponse
│   │   └── report.py           # ReportRequest/ReportResponse
│   ├── chains/
│   │   ├── __init__.py
│   │   ├── prompts.py          # 三个用户接口模板 + report 内部分析模板（均含 JSON 示例）
│   │   ├── parsing.py          # JSON 抽取 + 容错 + 字段收敛
│   │   ├── example_chain.py    # 单步 LCEL
│   │   ├── explain_chain.py    # 单步 LCEL
│   │   └── report_graph.py     # langgraph StateGraph（多步编排）
│   └── routers/
│       ├── __init__.py
│       └── ai.py               # 三个路由（单一 router，前缀 /ai/v1）
├── tests/
│   ├── conftest.py             # async FakeLLM + ASGI test-client fixture（不起模型网络）
│   ├── test_health.py
│   ├── test_example.py
│   ├── test_explain.py
│   ├── test_report.py          # 条件路由、快路径及图编排用例
│   ├── test_llm_request.py     # JSON mode/thinking 最终请求体
│   ├── test_deadlines.py       # 请求总 deadline 与取消传播
│   ├── test_lifespan.py        # AsyncClient/Runnable 生命周期
│   ├── test_parsing.py
│   └── test_no_database_imports.py   # 红线自动化校验
├── pytest.ini                  # asyncio_mode = auto（**必需**，见下）
├── requirements.txt            # 锁版本，含 langchain / langgraph 元包（依赖闭包无数据库驱动）
├── .env.example
└── README.md
```

**`pytest.ini` 是必需文件，不是可选项**：本项目**全部**用例都是 `async def`。
实测未配 `asyncio_mode` 时，`pytest-asyncio` 不接管，结果是
`PytestUnhandledCoroutineWarning: async def functions are not natively supported and were skipped.`
——**全部用例被 skip 且退出码仍为 0**。而各阶段闸口的判据正是「单测全绿」，
少了这一行会让闸口被静默欺骗。文件内容：

```ini
[pytest]
asyncio_mode = auto
```

**`main.py` 必须是应用工厂形态**（`create_app(*, http_client=None) -> FastAPI`），
不是模块级 `app = FastAPI(...)` 单例。理由：`httpx.AsyncClient` 按 §5 第 6 点
只能在 lifespan 内创建，而 `tests/test_llm_request.py` 又必须把这个 client
换成 `MockTransport` 版本才能离线捕获最终请求体。没有注入点，这两条要求无法同时满足。
模块级仍保留 `app = create_app()` 供 `uvicorn app.main:app` 直接使用。

**与 S3 §3.5 的差异说明**：S3 写的是 `routers/example.py` 等「每功能一路由文件」，
本设计合并为 `routers/ai.py`（单文件三路由）。
理由：三个路由共享同一个 router、同一组错误处理与依赖注入，拆三个文件会产生
三份重复的错误映射代码。**目录语义未变**（仍在 `routers/`），只是文件粒度更合并。
这是实现细节级别的偏离，不改动任何对外契约，故不触发契约回写。

---

## 4. 配置设计（`app/core/config.py`）

用 `pydantic-settings`（spec 指定），单一入口，**代码中不出现任何字面量密钥**。

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `DEEPSEEK_API_KEY` | **无默认值，但允许缺失** | 类型为 `SecretStr \| None = None`。**缺失时启动不崩**，调用时抛稳定错误（见 §7.2） |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | OpenAI 兼容 |
| `DEEPSEEK_MODEL` | `deepseek-flash` | `deepseek-chat` 已退役（research §2.1） |
| `DEEPSEEK_THINKING_ENABLED` | 固定 `false`（配置为 `true` 启动校验失败） | JSON mode 与 thinking 冲突，所有接口关闭思考（research §2.3） |
| `DEEPSEEK_MAX_RETRIES` | `2` | 有限重试 |
| `EXAMPLE_TIMEOUT_S` | `12` | example handler 的生成/解析总预算（含重试），留出 HTTP 序列化与传输余量，**< backend 的 15s**（见 §6.2） |
| `EXPLAIN_TIMEOUT_S` | `25` | explain handler 的生成/解析总预算（含重试），留出响应余量，**< 30s** |
| `REPORT_TIMEOUT_S` | `50` | report 整张图的生成/解析总预算（含两次调用、重试与解析），留出响应余量，**< 60s** |
| `LLM_ATTEMPT_TIMEOUT_S` | `5` | 单次模型 HTTP 尝试的超时上限；与请求级 deadline 职责分离（理由见下） |
| `LOG_LEVEL` | `INFO` |

客户端生命周期不作为环境变量：FastAPI lifespan 创建共享异步 HTTP client，并在 lifespan 中创建/销毁 feature Runnable 与 report 图；每次 lifespan 退出均关闭 client，并清除依赖它的缓存。

**为什么 key 缺失不阻止启动**：契约要求「失败抛 HTTPException，业务后端映射 5xxx」，
而**启动即崩**会让 backend 看到「连接被拒」→ 5002，与「key 未配置」无法区分。
选择「启动成功、调用时给出明确稳定 detail」，可观测性更好（health 端点能反映配置状态）。
但**health 端点不得回显 key 的值**，只报布尔。

---

## 5. 大模型客户端（`app/core/llm.py`）

```python
ChatOpenAI(
    model=settings.deepseek_model,
    base_url=settings.deepseek_base_url,
    api_key=settings.deepseek_api_key,
    max_retries=settings.deepseek_max_retries,
    timeout=settings.llm_attempt_timeout_s,
    http_async_client=shared_async_http_client,
    extra_body={
        "thinking": {"type": "disabled"},
        "max_tokens": <per feature>,   # ⚠️ 必须走 extra_body，见要点 4
    },
)

# JSON mode 是 OpenAI 标准顶层参数，不放进 DeepSeek 专属 extra_body。
json_llm = llm.bind(response_format={"type": "json_object"})

# report 的 analyze 与 compose 共享一个绝对请求 deadline；两次调用分别受单次尝试 timeout
# 和 max_tokens 限制。第二步只能使用总 deadline 的剩余时间，不能重新获得完整 50s。
# 模型/Runnable/图绑定当前 lifespan，退出时关闭 AsyncClient 并清除相关引用，不跨 lifespan 缓存。
``` 

要点与理由：

1. **不传 `temperature`**：官方文档明确 thinking 模式不支持该参数（设了也静默无效）。
   我们虽关掉 thinking，仍不传，避免「以为调了其实没调」的假象。确定性交给提示词约束。
2. **`timeout` 是单次尝试的上限**：每次模型 HTTP 尝试均须受限；请求级总 deadline 单独在 router/service 层实施（见 §4、§6.2）。

   取 `5s` 而非 `10s` 的理由：重试次数受**总 deadline 截断**，两者必须一起算。
   若单次 `10s` + `max_retries=2`（最坏 3 次 = 30s），则 example 的 12s 预算内
   第一次尝试就吃掉 10s、只剩 2s，**重试实际不可能发生**——「有重试策略」成了纸面条款。
   取 `5s` 后 example 的 12s 内可容纳 2 次尝试，explain 25s 内从容，report 50s 内
   两次调用各留余量。

   实测补充（`research §6.6`）：注入共享 `http_async_client` 时，`ChatOpenAI(timeout=)`
   **仍完全生效并覆盖** client 自身的 timeout（`ChatOpenAI(timeout=99)` +
   `AsyncClient(timeout=1.0)` → 等满 99s 才超时）。因此共享 client 的默认超时
   不会误伤长调用，单次尝试上限完全由本参数决定。
3. **`max_retries` 有限**：2 次（LangChain 默认也是 2，显式写出以免漂移）。
   重试只覆盖连接/5xx 类瞬时错误；4xx 不重试（LangChain 内建行为）。
4. **`max_tokens` 上限，且必须经 `extra_body` 传入**：JSON 模式下必须设，否则截断会导致解析失败
   （research §2.3 第 5 条）。example / explain / report compose 分别设 `512 / 1024 / 1536`；
   report analyze 使用专用较小上限 `512`。每次调用都另受请求级总 deadline 约束。

   **🔴 `max_tokens` 不能作为 `ChatOpenAI(...)` 构造参数**（实测，`research §6.1`）：
   `langchain-openai==1.7.2` 会把 `max_tokens=` 改名为 `max_completion_tokens` 发出，
   而 DeepSeek 只认 `max_tokens`。

   | 传参方式 | 最终请求体 | DeepSeek |
   |---|---|---|
   | `ChatOpenAI(max_tokens=512)` | `max_completion_tokens: 512` | ❌ |
   | `model_kwargs={"max_tokens":512}` | `max_completion_tokens: 512` | ❌ |
   | **`extra_body={"max_tokens":512}`** | **`max_tokens: 512`** | ✅ |

   合并后的 `extra_body` 实测最终 body 顶层键：
   `['max_tokens', 'messages', 'model', 'response_format', 'stream', 'thinking']`
   —— 同时满足「顶层 `response_format`」与「顶层 `thinking.type=disabled`」两个断言，
   且**没有** `extra_body` 字面包装层。
5. **JSON 模式在 Runnable 层绑定**：每个 feature 的 `ChatOpenAI` 实例创建后，
   用 `llm.bind(response_format={"type": "json_object"})` 生成供 LCEL 使用的 Runnable。
   `response_format` 是 OpenAI Chat Completions 的标准顶层参数；不要把它放进
   DeepSeek 专属的 `extra_body`。report 的 analyze 与 compose 两次调用都必须走此绑定。
6. **异步 HTTP 客户端生命周期**：通过 FastAPI lifespan 创建并关闭共享的 `httpx.AsyncClient`，
   以 `http_async_client` 注入 `ChatOpenAI`；不要由每个请求单独创建连接池，也不要在模块导入时
   创建客户端。客户端需采用可取消的异步 transport，供请求级 deadline 取消进行中的 I/O，且应用关闭时正确 `aclose()`。
7. **总请求 deadline 与单次尝试 timeout 分离**：每个接口在 router/service 层设请求级总 deadline，
   example/explain/report 分别为 `12/25/50s`；ChatOpenAI 的 HTTP timeout 是每次实际尝试的上限，
   不能把请求级值误当成每次重试都能完整使用的时间。report 两次 LLM 调用、退避及解析共同受
   50s deadline 限制。deadline 到期统一映射 `ERR_LLM_TIMEOUT`；应通过慢 FakeLLM 测试验证总时限，
   并确认取消/超时不会让第二步在请求结束后继续执行。
8. **思考模式必须关闭**：JSON 输出与 DeepSeek thinking 有已知冲突，因此
   `DEEPSEEK_THINKING_ENABLED=true` 属于无效且危险配置。Settings 应拒绝该配置
   （给出明确配置错误），生产请求始终发送 `thinking: disabled`；不得静默忽略配置。 
9. **资源缓存限定**：连接池、无状态客户端配置和当前 lifespan 内已编译图可复用；请求/响应/生成报告不得缓存。
   含有 lifespan `AsyncClient` 引用的 ChatOpenAI、Runnable 与图仅能在该 lifespan 内创建和缓存，退出时清理，
   不得用模块级 `lru_cache` 捕获 client 或跨 lifespan 复用。
10. **max_tokens 分配**：example / explain / report compose 分别设 `512 / 1024 / 1536`；
    report analyze 使用较小上限 `512`。每次调用另受请求级总 deadline 限制。
    一律经 `extra_body` 下发（见要点 4）。

锁定的 `langchain-openai==1.7.2` 必须做离线 HTTP 请求体测试，确认 `bind(response_format=...)`
将 `response_format` 放在最终 JSON body 顶层，并确认构造参数 `extra_body={"thinking": {"type": "disabled"}}`
经 OpenAI SDK 合并后，最终 body 含顶层 `thinking: {"type": "disabled"}`（不是字面 `extra_body` 包装层）；
不能只用模型实例属性测试推断线上请求体正确。再通过真实 DeepSeek 调用验证供应商接受该组合。
这两个验证层次分别证明 LangChain 组包行为与供应商端到端兼容性。 

> **版本注**：以下参数面在 `langchain-openai 1.7.2` 上**逐个实测验证通过**
> （`research §1.5`）。注意 `inspect.signature(ChatOpenAI.__init__)` **看不到**这些参数
> （由 pydantic 字段机制接管）——不要用签名探测来判断支持性，必须实际构造。

---

## 6. 调用编排（chains）

每条线上链均使用异步 `ainvoke`；通过 `asyncio.timeout` 为全请求生成/解析阶段施加一次总 deadline。HTTP handler 不得用同步 `invoke`。

### 6.1 通用形态（LCEL）

```python
prompt | json_llm | StrOutputParser() # JSON mode，拿到原始字符串
        ↓
   parse_json_object(text)             # 容错解析 → dict
        ↓
   ExampleResponse(**filtered)         # Pydantic 收敛（缺字段用 "" 补）
```

### 6.2 超时预算（为什么自控超时必须更短）

backend 的读超时是硬边界：**一旦 backend 先超时，它就会断开连接**，
此时我们的重试与后续处理全是白做工，还会额外占用一个线程。
所以本服务必须在 backend 超时**之前**结束并返回。

| 功能 | backend | 本服务自控 | 预留 |
|---|---|---|---|
| example | 15s | 12s | 3s（含建连 + 序列化） |
| explain | 30s | 25s | 5s |
| report | 60s | 50s | 10s |

> 注意 backend 的**连接超时**是 `min(5000, timeoutMs)`，与本服务的读超时无关。

### 6.3 学习报告 = 带确定性条件路由的多步编排（S3 §3.5「多步 Chain」）

**实现方式：`langgraph.StateGraph`**（用户要求使用 langgraph；它是 `langchain 1.3.18` 的必需依赖）。

```
studyStats (来自请求)
   │
   └─ node "digest"（纯 Python，非 LLM）：格式化摘要 + 数据可用性检查
          │
          ├─ topMistakeWords 为空 且 masteryRate == 100
          │      └─ digest 直接生成谨慎措辞的完整三段报告 → END（0 次 LLM）
          │
          └─ 其他情况 → node "analyze"（LLM #1）
                          │
                          └─ node "compose"（LLM #2）→ END
```

backend 已在调用 ai-service 前拦截 `studyDays < 3`（`AiServiceImpl.report`），因此 ai-service
不再重复判断学习天数。这里的条件分支专门处理无明显错题薄弱项的情形：仅当
`topMistakeWords` 为空且 `masteryRate == 100` 时，`digest` 写入完整 `report` 并将
`route` 设为 `complete`；否则将 `route` 设为 `analyze`，流程继续原两步 LLM 分析。
不得仅凭空错题列表就判断用户整体已掌握，也不得把掌握度 100 描述成「全部学习内容已掌握」。
快速路径用固定模板构造报告，避免无证据的个性化判断：`overview` 摘述累计学习天数、累计学习词数和近两周打卡率；
`weakness` 固定表达「当前统计中暂无明显薄弱错题」；`suggestion` 给出中性、可执行的持续复习建议。
三个字段必须非空，数字只取请求数据，不调用模型。最终仍经 `ReportResponse` 校验。 

共享状态用 `TypedDict`（`total=False`，每节点只写自己那份键）：

```python
class ReportState(TypedDict, total=False):
    stats: dict        # 输入
    digest: str        # digest 产出的紧凑摘要
    route: str         # "complete" 或 "analyze"
    analysis: dict     # analyze 产出的内部结构化结论
    report: dict       # digest 快速路径或 compose 产出

g = StateGraph(ReportState)
g.add_node("digest", digest)
g.add_node("analyze", analyze)
g.add_node("compose", compose)
g.set_entry_point("digest")
g.add_conditional_edges(
    "digest",
    lambda state: state["route"],
    {"complete": END, "analyze": "analyze"},
)
g.add_edge("analyze", "compose")
g.add_edge("compose", END)
report_graph = g.compile()
```

**节点契约**：`digest` 必须总是返回 `route` 和 `digest`；快速路径还必须返回完整
`report`（字段恰为 `overview` / `weakness` / `suggestion`，均为非空字符串）。`analyze`
必须返回 `analysis`。`compose` 入口显式检查 `analysis` 存在且非空，缺失时抛稳定的内部错误
（按统一异常映射成为非 2xx），避免 `TypedDict(total=False)` 下拼写错误静默流入提示词。
`compose` 仅在校验后调用 LLM 并写入完整 `report`。

**为何保留 analyze + compose**：契约要求报告分三段且互相呼应
（`weakness` 要来自数据、`suggestion` 要针对 `weakness`）。
单次调用容易写出一段通用鸡汤；先分析再生成，让 `suggestion` 有条件地引用分析结论。
**代价**：常规路径两次 LLM 调用 → 延迟与 token 翻倍；确定性快速路径可跳过两次调用。
因此：
- 只给 report 用（它有 60s 预算），example/explain 保持单步 LCEL；
- 两步共用同一 JSON-mode model 与超时预算，step1 失败即整体失败（不做「降级为单步」的隐式降级——
  契约里 ai-service 不该有隐式降级，降级决策属于 backend）；
- 快速路径是按已知统计事实生成契约内的确定性结果，不是模型故障时的替代/降级逻辑。

**为何不用 langgraph 包 example/explain**：它们是「一次 LLM 调用 + 一次解析」，
`StateGraph` 包一层只有 1 节点 1 边，是仪式性复杂度。S3 §3.5 要求的是
「多步 Chain」，不是「所有东西都上图」。

**可测性**：`compile()` 返回对象有 `ainvoke`；单测可直接替换节点内的
LLM Runnable（节点函数持有 FakeLLM），无需跑网络；通过调用计数确认快速路径为 0 次、
常规路径为 2 次 LLM 调用。产品请求与图执行统一使用 `ainvoke`，同步 `invoke` 仅可用于离线开发脚本，不能用于服务请求。

**JSON 输出模式**：report 的 `analyze` 与 `compose` 均使用 §5 绑定的 JSON-mode Runnable；
`analysis` 使用专用内部 Pydantic 模型（至少包含可验证的优势/薄弱点结论），要求 JSON 对象并经统一解析/字段收敛，不以自然语言自由文本绕过 JSON 约束；它是内部结构，不改变 HTTP 契约字段。
快速路径不调用模型，但仍由相同 `ReportResponse` Pydantic schema 校验最终字段。

> `add_conditional_edges` 与 `END` 的用法按 LangGraph StateGraph 条件路由 API；实际版本锁定为
> `langgraph==1.2.16`，执行前置验证时以该锁版 API 实测通过为准。

### 6.4 解析容错（`parsing.py`）

模型可能返回：纯 JSON / ```` ```json ... ``` ```` 围栏 / 前后带解释文字。

策略（按顺序）：
1. `json.loads(text)` 直接成功 → 用。
2. 剥离 Markdown 代码围栏（```json / ```）后重试。
3. 用括号配平扫描抽取**第一个完整的 `{...}` 对象**（逐字符数 `{}` 深度，
   跳过字符串内的括号）后重试。
4. 全失败 → 抛 `HTTPException(502, detail=ERR_BAD_MODEL_OUTPUT)`。

**不做**的容错：不用正则从文本里猜字段、不用 LLM 二次修复、不返回半截文本
（prd 明确要求「解析失败 → 抛 HTTPException，不返回半截文本」）。

### 6.5 字段收敛

解析出的 dict **可能缺字段或字段类型不对**（模型偶尔返回数字/数组）。
收敛规则：**白名单字段 + 缺失补 `""` + 非字符串转 `str()` + strip**。
`example` 的 `example` 字段若为空 → 抛 502（因为空例句是**无意义成功**，
返回给 backend 会让用户看到空白；backend 侧对 `example` 有「回退库内例句」的兜底，
但它只在**收到 AI 失败**时触发，收到空串只会记成功——所以必须由我们判失败）。

---

## 7. 错误设计（`app/core/errors.py`）

### 7.1 稳定 detail 常量表

| 常量 | detail 文本 | HTTP | 触发 |
|---|---|---|---|
| `ERR_LLM_TIMEOUT` | `ai_service_timeout` | 504 | LLM 调用超时 |
| `ERR_LLM_FAILED` | `ai_service_llm_failed` | 502 | LLM 连接/5xx/其它异常 |
| `ERR_LLM_NOT_CONFIGURED` | `ai_service_llm_not_configured` | 503 | 未配置 API key |
| `ERR_BAD_MODEL_OUTPUT` | `ai_service_bad_model_output` | 502 | JSON 解析/收敛失败 |
| `ERR_EMPTY_RESULT` | `ai_service_empty_result` | 502 | 关键字段为空 |

**全部为 ASCII 小写下划线短串**——理由：
①契约要求「稳定字符串」（backend 会把它当日志线索，不解析内容）；
②ASCII 规避 GBK 终端下的 `UnicodeEncodeError`（handoff §6 记录过这个坑）；
③便于 grep 定位。

`detail` 用 `{"code": "...", "message": "..."}` 结构化？**不**——FastAPI 的
`detail` 直接作为响应 JSON。这里用**纯字符串**，因为：
- 契约未定义 ai-service 的错误体结构；
- backend 只按异常类型映射，不解析 detail；
- 保持最小惊讶（FastAPI 原生 `HTTPException` 形状）。

### 7.2 未配置 key 的行为

`llm.py` 在构造时报 `ERR_LLM_NOT_CONFIGURED`（503）而非启动崩溃（见 §4 理由）。
`GET /health` 返回 `{"status":"ok","llmConfigured":true|false}`，**不回显 key**。

### 7.3 异常到 HTTPException 的映射（统一在 router 层）

```python
try:
    return await chain_fn(request)
except HTTPException:
    raise                                  # 已是我们构造的稳定错误，原样上抛
except (TimeoutError, openai.APITimeoutError):
    raise HTTPException(504, ERR_LLM_TIMEOUT)
except Exception as exc:
    log.warning(...)                       # 记录异常类名，不记请求体
    raise HTTPException(502, ERR_LLM_FAILED)
```

**注意**：`except Exception` 之后**必须**抛出，不能吞（spec 禁「吞异常」）。
这里**只记录、只转换**，不打印堆栈到 stdout（spec 禁 `print`）。

---

## 8. 日志设计（`app/core/logging.py`）

- 格式：`%(asctime)s %(levelname)s %(name)s %(message)s`（spec 指定）。
- 在 `core/` 配一次，`main.py` 导入即生效；Uvicorn access log 保持开启。
- 记录内容（对齐 spec/logging-guidelines「每一次 AI 服务调用：功能名、耗时、结果」）：

```
INFO  ai_service.example 调用开始 word=abandon context=CET4
INFO  ai_service.example 调用完成 cost_ms=1840
WARN  ai_service.example 调用失败 cost_ms=12030 detail=ai_service_timeout
```

- **不记录**：API key、完整请求体（含用户学习数据）、提示词全文。
  单词文本可记（它来自词库、非用户隐私），但 `wrongContext`（含用户学习行为）**不记**。

---

## 9. 测试设计

### 9.0 ⚠️ 测试基础设施的两个硬性前提

**① `pytest.ini` 必须有 `asyncio_mode = auto`。** 否则全部 async 用例被 skip、
退出码仍 0（见 §3 说明）。这是「测试全绿」这一判据成立的前提。

**② lifespan 测试不能用裸 `ASGITransport`。** 实测（`research §6.7`）：

| 方式 | lifespan 事件 |
|---|---|
| `AsyncClient(transport=ASGITransport(app))` | **`[]`（从未启动）** |
| `fastapi.testclient.TestClient(app)` 作上下文管理器 | `['startup', 'shutdown']` ✅ |
| 手动 `app.router.lifespan_context(app)` 进/出 | ✅ 且可重复两轮 |

用裸 `ASGITransport` 会让 `test_lifespan.py` 在「lifespan 从未运行」的假前提下
全绿——它测不到任何东西却打印通过。

**采用方案**：`app.router.lifespan_context(app)` 手动 `__aenter__` / `__aexit__`。
不新增依赖（无需 `asgi-lifespan`），且能精确控制「连续起停两轮」这一用例。
业务路由的 request 级测试仍可用 `ASGITransport`（它们不依赖 lifespan 状态，
因为被测的 LLM Runnable 由 FakeLLM 注入替代）。

### 9.1 策略：**替换 LLM，保留真实链路**

不同验证目的使用不同替身：
- Chain/graph 业务单测通过 `llm_provider` 注入 `FakeLLM` Runnable，返回 `AIMessage`，保留 prompt → JSON 解析 → 字段收敛 → Pydantic 响应校验的真实链路；测试路由与 LangGraph 执行使用 async 测试及 `ainvoke`。
- HTTP 请求参数另用 MockTransport/等效 HTTP mock，捕获锁定版本 OpenAI SDK 最终发出的 HTTP body；不能用 FakeLLM 单测代替请求序列化验证。
- 超时测试使用支持协程取消的 Fake Runnable；设置事件确认取消已经到达模型边界，断言 report 的 `compose` 未被启动。不可只断言客户端收到了超时响应。

理由：mock 掉 LLM 边界会漏掉提示词渲染/解析 bug；而纯 Fake 又验证不到 OpenAI SDK 是否把 JSON mode 与 DeepSeek 扩展参数正确序列化。两类测试各自覆盖不同边界。 

### 9.2 用例矩阵

| 文件 | 覆盖 |
|---|---|
| `test_example.py` | async 成功（JSON）/ 围栏 JSON / 空 example → 502 / LLM 抛异常 → 502 |
| `test_explain.py` | async 成功 / LLM 超时 → 504 / `wrongContext` 空串仍成功 |
| `test_report.py` | 常规成功（FakeLLM 依次返回分析+正文，断言调用 2 次）/ 无明显错题快速路径（`topMistakeWords=[]` 且 `masteryRate=100`，断言调用 0 次并返回三段非空文案）/ 空错题但掌握度非 100 仍走常规路径 / step1 异常 → 502 / compose 缺失 analysis 时失败 |
| `test_deadlines.py` | example/explain/report 总 deadline 生效；deadline 取消传播至 Fake Runnable；report 超时时 compose 不启动；无后台模型任务遗留 |
| `test_lifespan.py` | 重复启动/关闭 FastAPI lifespan；确认共享 AsyncClient 每轮重建并关闭，依赖其的 Runnable/图不跨 lifespan 泄漏；仅连接池/客户端/图可复用，不缓存请求与响应结果 |
| `test_llm_request.py` | 使用 HTTP mock 捕获锁版 `langchain-openai==1.7.2` 最终发出的请求体，无外网；断言顶层 `response_format={"type":"json_object"}` 与顶层 `thinking.type=disabled` 同时存在；覆盖 example/explain 和 report 两个 LLM 节点 |
| `test_parsing.py` | 纯 JSON / 围栏 / 前后噪声 / 未闭合 / 空串 / 非 dict（数组） |
| `test_lifespan.py` | **必须**经 `app.router.lifespan_context(app)` 手动进入；连续两轮起停，断言每轮新建 client、旧 client 已 `aclose()`、图/Runnable 不复用旧 client |
| `test_health.py` | `/health` 200 且不回显 key / `/docs` 200 / OpenAPI 含三路由 |
| `test_no_database_imports.py` | 遍历 `app/**/*.py` 的 AST import，断言无 `mysql`/`pymysql`/`psycopg`/`sqlalchemy`/`sqlite3`；并断言 `requirements.txt` 无这些包名 |

`test_no_database_imports.py` 是本任务最特殊的一个测试——**把红线变成可执行断言**，
而不是靠人肉 grep（handoff §6 记录过 grep 在 GBK 终端数错的问题）。

**总 deadline 实现要求**：路由/链全程使用 async `ainvoke`（report 使用图 `ainvoke`），通过单个 `asyncio.timeout(settings.<FEATURE>_TIMEOUT_S)` 或等效绝对 deadline 覆盖模型尝试、自动重试/退避、解析和 schema 收敛；配置字段对应 `EXAMPLE_TIMEOUT_S`、`EXPLAIN_TIMEOUT_S`、`REPORT_TIMEOUT_S`。不使用线程池包同步 `invoke`。超时转换为稳定 `ERR_LLM_TIMEOUT`。HTTP 传输层必须是可取消的异步请求；超时后请求任务结束，不能留存后台工作。实现与单测需针对锁定版本验证这一取消语义。 

### 9.3 真实路径测试（默认跳过）

`tests/test_live_deepseek.py`：仅当 `DEEPSEEK_LIVE_TEST=1` 且 key 存在时运行，
真实调用三个接口并断言契约字段非空、耗时在预算内。默认 `pytest.mark.skipif` 跳过，
避免 CI/无网环境误失败。

---

## 10. 依赖锁版（`requirements.txt`）

> 版本基线由用户指定：`langchain==1.3.18`（2026-10-17）。
> 全家族升至 1.x，实测版本与依据见 `research/01-deepseek-and-deps.md` §1。

```
# —— Web 框架 ——
fastapi==0.115.5
uvicorn==0.32.0
pydantic==2.9.2
pydantic-settings==2.6.1

# —— LLM（用户指定 1.x 基线）——
langchain==1.3.18
langchain-core==1.6.12
langchain-openai==1.7.2
langgraph==1.2.16
openai==3.33.0

# —— 测试 ——
pytest==8.3.3
pytest-asyncio==0.24.0
httpx==0.28.1          # async client / tests（venv 实测解析值）
```

### 10.1 为什么可以装 `langchain` 元包（推翻原结论）

原设计据 0.3.x 认为「元包依赖 SQLAlchemy，必须避开」。**实测 0.3.x 与 1.x 差异**：

| 版本 | ✱SQLAlchemy 依赖 |
|---|---|
| 0.3.7 | ✅ 有（`SQLAlchemy>=1.4,<3`）→ 必须避开 |
| **1.3.18** | ❌ 无；仅 `langchain-core` + `langgraph` + `pydantic` |

且 `langchain==1.3.18` 全闭包（39 个包）逐名筛查数据库包 → **NONE**。
所以按标准方式装元包，红线依然成立。这也正是用户要求的形态（用 `langchain` + `langgraph`）。

### 10.2 `langgraph` 必需，且仅用于 report

`langgraph` 是 `langchain 1.3.18` 的**必需依赖**（`>=1.2.11,<1.3.0`），
不装就装不上 langchain。使用范围见 §6.3：**仅 report 走 StateGraph，example/explain 保持 LCEL**。

### 10.3 版本选择依据

全部取**隔离 venv 已实测可导入**的版本（`research §1.2`）：
- `langchain-openai 1.7.2` 要求 `langchain-core>=1.6.10,<2` → 实装 1.6.12 满足；
- `openai` 实装 3.33.0，落在 `>=2.45,<4` 内；
- `pydantic 2.9.2` 满足 langchain-core 的 `>=2.7.4,<3`。

> ⚠️ `langchain-core 1.6.12` 要求 `httpx>=0.23,<1`，venv 解析出 `httpx 0.28.1`（与本机全局的 0.27.2 不同）。
> 交付时以 `requirements.txt` 为准；README 说明建议用 venv 而非全局环境复现。

---

## 11. 兼容性、回滚与风险

### 11.1 兼容性

| 维度 | 结论 |
|---|---|
| 对 backend | **零改动**。契约字段未变，仅实现方（ai-service）从「不存在」变为「存在」 |
| 对契约文档 | **无需回写**（§9 字段未变）。供应商现状（模型名退役）属**运维信息**，见 §11.3 |
| 对既有 `g5-smoke.py` | **可能需要关注**：该脚本刻意要求 ai-service 不可达。若测试时 ai-service 在跑，前置断言会 `exit 2`。→ `implement.md` 中明确「跑 g5 前确保 8000 无监听」 |

### 11.2 回滚形状

- 本服务是**新增目录** `ai-service/`，不修改任何既有文件（除可能的 `.gitignore` 补条目）。
- 回滚 = 删除 `ai-service/` 目录 + 还原 `.gitignore` → backend 自动回到「AI 不可用 → 降级」路径，
  **系统仍可运行**（这正是架构解耦的价值，也是回滚安全的证明）。
- 因此**不需要** feature flag、不需要数据迁移、不需要灰度。

### 11.3 已识别风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 模型名再变（DeepSeek 迭代快） | 运行时 401/400 | 做成 `DEEPSEEK_MODEL` 环境变量；README 写明查证链接 |
| 思考模式默认值可能再次变化 | 延迟/空 content | 显式传 `thinking: disabled`，不依赖默认值 |
| 15s 预算对例句接口偏紧 | backend 判 5001 → 前端降级 | 自控 12s；真实测耗时；若偏紧则调提示词长度与 `max_tokens` |
| 报告两步调用总延迟 | 可能接近 60s | 自控 50s；step1 用更小 `max_tokens`；真实测 |
| `langchain-openai` 丢弃 `reasoning_content` | 与思考模式配合削弱 | 已关闭思考，不依赖该字段 |
| 全局 Python 环境被改动 | 影响其它项目 | **改用隔离 venv 验证**（`research §1.2`）；`requirements.txt` 锁版本；`.venv/` 已 gitignore |
| 1.x 栈与旧教程/旧文档不兼容（社区大量 0.2/0.3 示例） | 照抄旧示例失败 | 本任务把**实测版本与 API 证据**写入 `research`；实现只依赖 `langchain-core` 稳定层（`ChatPromptTemplate`/LCEL/`ChatOpenAI`） |
| `langgraph` 图状态键写错（`total=False` 下静默丢键） | 报告缺字段 | 节点函数**返回完整增量**；单测断言 state 含 `analysis` 与 `report` 两键 |

### 11.4 待确认（不阻塞实现）

`docs/接口详细设计.md` §9 的「供应商说明」与我实测的现状有两处**事实性过时**：
①未提模型名；②未提「OpenAI 兼容但 thinking 默认开启」。
这不影响契约字段，但会让后来者照抄出 `deepseek-chat`。
→ 是否回写契约文档的**说明段落**（非字段），实现完成后再向用户确认，不擅自改冻结文档。
