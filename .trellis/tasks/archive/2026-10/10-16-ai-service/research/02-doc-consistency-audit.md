# 文档一致性审查（2026-10-17）

对象：更新后的 `prd.md` / `design.md` / `implement.md` / `research/01-deepseek-and-deps.md`。
方法：**每一条可验证的论断都在隔离 venv（`langchain 1.3.18` 全家族）里跑一遍**，
不靠阅读推断。判定只有三种：`成立` / `不成立（已修正）` / `表达冲突（需裁决）`。

环境：Python 3.12.7 + 隔离 venv，`langchain 1.3.18` / `langchain-core 1.6.12` /
`langchain-openai 1.7.2` / `langgraph 1.2.16` / `openai 3.33.0` / `fastapi 0.115.5` /
`starlette 0.41.3` / `httpx 0.28.1` / `pytest 8.3.3` / `pytest-asyncio 0.24.0`。

---

## A. P0 — 会导致实现错误或测试假绿（必须先裁决）

### A1. `max_tokens` 会被 SDK 改名成 `max_completion_tokens`（DeepSeek 不认）

`design.md §5` 与 `implement.md` 阶段 2 都写 `ChatOpenAI(max_tokens=<per feature>)`。

实测最终请求体顶层键：

| 传参方式 | 实际发出的键 | DeepSeek 是否认 |
|---|---|---|
| `ChatOpenAI(max_tokens=512)` | `max_completion_tokens: 512` | ❌ |
| `model_kwargs={"max_tokens":512}` | `max_completion_tokens: 512` | ❌（且告警） |
| **`extra_body={"max_tokens":512}`** | **`max_tokens: 512`** | ✅ |
| `ChatOpenAI(max_completion_tokens=512)` | `max_completion_tokens: 512` | ❌ |

**后果**：JSON mode 下 `max_tokens` 是本项目唯一防截断手段（`research §2.3` 第 5 条）。
若被供应商忽略，模型可能一路输出到供应商默认上限（或直到仍被截断），
表现为「偶发解析失败」，极难定位。

**结论**：`max_tokens` **必须经 `extra_body` 传入**，不能作为构造参数。
（用户已确认按实测处理。）

### A2. `httpx.ASGITransport` 不执行 lifespan → `test_lifespan.py` 会假绿

`implement.md` 阶段 5 第 17 条：「可用 `httpx.AsyncClient` + `ASGITransport` 和
pytest async 插件；确保 lifespan 在测试中运行并释放共享 AsyncClient」。

实测（FastAPI + `lifespan=`）：

| 方式 | lifespan 事件 |
|---|---|
| `AsyncClient(transport=ASGITransport(app))` | **`[]`（根本没跑）** |
| `fastapi.testclient.TestClient(app)` 作上下文管理器 | `['startup', 'shutdown']` ✅ |

**后果**：`test_lifespan.py` 的全部断言会在「lifespan 从未启动」的假前提下通过——
它测不到任何东西，却打印绿色。这是最危险的一类缺陷。

**可选修法**（择一，实现时定）：
1. 引入 `asgi-lifespan`（`LifespanManager(app)`）包住 `ASGITransport` 测试；
2. 手动 `ctx = app.router.lifespan_context(app); await ctx.__aenter__() / __aexit__()`。
   已实测该 API 在 `starlette 0.41.3` 可用，且**可重复进入两次**（满足「重复 lifespan」用例）：
   第一轮 `['startup', 'in-request', 'shutdown']`，第二轮 `['startup', 'shutdown']`。

推荐方案 2：不新增依赖，且能精确控制两轮 lifespan。

### A3. 缺 pytest 配置 → 所有 async 测试被 **skip** 且 `pytest` 仍 exit 0

`design.md §3` 的目录清单里**没有** `pytest.ini` / `pyproject.toml` / `setup.cfg`，
但 `requirements.txt` 加了 `pytest-asyncio==0.24.0`。

实测（`pytest-asyncio` 已安装但无 `asyncio_mode` 配置）：

```
PytestUnhandledCoroutineWarning: async def functions are not natively supported and have been skipped.
1 skipped, 1 warning in 0.03s      ← exit code 0
```

加了 `pytest.ini` 的 `asyncio_mode = auto` 后：

```
1 passed in 0.03s
```

**后果**：本项目**全部**测试（三个路由 + deadline + lifespan + 图编排）都是 async。
少了这一行配置，`python -m pytest -q` 会「全部 skip、退出码 0」，
而 `implement.md` 各阶段的闸口判据正是「单测全绿」——**闸口会被静默欺骗**。

**结论**：`design.md §3` 结构清单需补 `pytest.ini`（或等价 `[tool.pytest.ini_options]`），
并显式写 `asyncio_mode = auto`。

### A4. `DEEPSEEK_API_KEY` 的「必填」与「启动不崩」自相矛盾

`design.md §4` 同一张表里：`DEEPSEEK_API_KEY` = 「**无默认**（必填）」+ 「缺失时**启动不崩**」。

实测：

```python
class S1(BaseSettings):
    deepseek_api_key: SecretStr      # 无默认值
S1()  → ValidationError: missing ('deepseek_api_key',)     # 启动即崩 ❌

class S2(BaseSettings):
    deepseek_api_key: SecretStr | None = None
S2()  → 构造成功，值为 None                                 # 启动不崩 ✅
```

**结论**：字段必须声明为 `SecretStr | None = None`，把「必填」的语义下移到
**调用时**（无 key → `ERR_LLM_NOT_CONFIGURED` 503）。表里「（必填）」应改为
「无默认值，但允许缺失；缺失时调用期报 503」。

顺带实测（两条都要写进实现约束）：
- `repr(S2(deepseek_api_key="sk-abc..."))` → `deepseek_api_key=SecretStr('**********')`，
  **`repr` 不含 `sk-`**。SQL 里 `implement.md` 的 `assert 'sk-' not in repr(s)` 由此成立。
- `SettingsConfigDict(extra="ignore")` 会**静默忽略**拼错的环境变量名
  （实测 `S3(bar="x")` 不报错）。可以接受（对旧 `.env` 友好），但需知情：
  `DEEPSEEK_API_KEY` 拼错时表现为「key 未配置」，而不是启动报错。

---

## B. P1 — 会导致自查/验证命令永久失败或指标失真

### B1. `implement.md §9` 红线自查命令会**自我命中**

```bash
grep -rniE "mysql|pymysql|psycopg|sqlalchemy|sqlite|database" ai-service/app ai-service/tests ai-service/requirements.txt
```

`tests/test_no_database_imports.py` 按设计**必须**包含这些禁用词（它是断言清单）。
实测该命令输出：

```
/tmp/aitest/tests/test_no_database_imports.py:1:FORBIDDEN = ("mysql", "pymysql", ...)
>>> FAIL（自我命中）
```

**修法**：命令扫描 `ai-service/app` + `ai-service/requirements.txt`；
对 `tests/` 用 AST/白名单方式（红线测试本身即机械证明），或在 grep 里
`--exclude=test_no_database_imports.py`。否则阶段 9 闸口永远无法通过。

### B2. `grep -rn "sk-" ai-service/` 也会命中占位符与测试假 key

`.env.example` 需要一个占位值，`tests/` 需要一个假 key（如 `sk-not-real`）。
该命令会命中它们并报 FAIL。

**修法**：要么占位符不用 `sk-` 前缀（如 `your-deepseek-api-key-here`），
要么命令加 `--exclude=.env.example --exclude-dir=tests`，并额外断言真实 key 长度/前缀
不出现在 `git ls-files` 的内容里。

### B3. `max_retries=2` × `LLM_ATTEMPT_TIMEOUT_S=10` 与请求级预算不自洽

`design.md §4/§5`：单次尝试 10s、重试 2 次 → 最坏 3 次尝试 = 30s。
但请求级 deadline 是 example **12s** / explain 25s / report 50s。

即：**example 的重试在预算内几乎不可能发生**（第一次尝试 10s 后只剩 2s），
report 两次调用各 3 次尝试 = 60s > 50s。

这不是正确性 bug（deadline 会兜住，映射 `ERR_LLM_TIMEOUT`），但有两个后果：
①「有重试策略」在 example 上近乎空转，验收项「大模型调用有重试策略」实际不可观测；
②文档未说明这一取舍，后来者会以为重试真的在生效。

**修法（择一，实现时定）**：
- 把 `LLM_ATTEMPT_TIMEOUT_S` 降到 **5s**（example 12s 内可容纳 2 次尝试；report 50s 内从容）；
- 或保留 10s，但在 `design.md §5` 明确写「report/example 的重试受总 deadline 截断，
  重试主要用于 explain 与 report 的第一步」。

推荐前者：5s 单次尝试对短文本 JSON 生成绰绰有余，且让重试真正有意义。

### B4. `test_llm_request.py` 缺少「如何把 mock client 注入 lifespan」的说明

`design.md §9.1` 要求「用 MockTransport 捕获锁版 SDK 最终发出的 body」，
但同一份设计又规定 `httpx.AsyncClient` **只在 lifespan 里创建**（§5 第 6 点，禁模块级创建）。

两者组合起来，测试必须能替换 lifespan 创建的那个 client，否则无法离线捕获。
文档未给出注入点。

**修法（实现时采用）**：`main.py` 暴露 `create_app(http_client_factory=None)` 工厂，
`http_client_factory` 默认 `lambda: httpx.AsyncClient(...)`；测试注入 MockTransport 版本。
需在 `design.md §3` 的 `main.py` 说明里补一句「应用工厂形态，便于测试注入 HTTP 客户端」。

---

## C. P2 — 表述建议（不影响正确性）

### C1. `checkInRate` 是「近两周打卡率」，不是全周期

`backend` 的 `buildStudyStats`：`checkInRate = 近14天打卡天数 / 14`（`Constants.REPORT_RECENT_DAYS = 14`）。
`design.md §6.3` 的快速路径已经正确写成「近两周打卡率」✅。
但 `prompts.py` 的 report 模板若泛写「打卡率 85%」，模型会误读为「历史全周期」。
建议提示词里显式带单位说明（「近两周打卡率」）。

### C2. `masteryRate` 分母为 0 时返回 100

`MasteryRates.percent` 在 `totalMistakes <= 0` 时返回 **100**（语义：没有未掌握的错题）。
因此「`topMistakeWords=[]` 且 `masteryRate=100`」的快速路径，实际会命中
**「从未答错过任何词」**的用户。`design.md §6.3` 的措辞约束
（不得说「已掌握全部学习内容」，只说「暂无明显薄弱错题」）**正好匹配这个语义** ✅，
无需修改，仅记录以免实现时写过头。

### C3. 契约文档 §9.2 的示例 `wrongContext` 与 backend 实际值不同

契约示例：`"用户误选「举办」"`；backend 实际：`"该词在复习中答错，累计错误 N 次"`
（`research §3.4` 已记录）。文档间**已一致**（设计明确要求不得假设有误选项）✅。
但契约 §9 本身仍是过时示例——属 §11.4「待确认」，**不阻塞**。

---

## D. 逐项核对结论（其余论断均成立）

| # | 论断 | 验证方式 | 结论 |
|---|---|---|---|
| 1 | `langchain 1.3.18` 闭包无 SQLAlchemy | 隔离 venv 安装 + `md.requires()` 筛查 | ✅ 成立 |
| 2 | `langgraph 1.2.16` 是 langchain 1.3.18 的必需依赖 | `requires()` | ✅ 成立 |
| 3 | `StateGraph` 条件路由 + 快速路径 | 实跑 `ainvoke`，调用计数 0 / 2 | ✅ 成立 |
| 4 | `TypedDict(total=False)` 下节点增量合并 | 实跑，state 含 `analysis` 与 `report` | ✅ 成立 |
| 5 | `asyncio.timeout` 取消能穿透 Runnable 与图节点 | 慢 Runnable + 慢节点，`CancelledError` 到达 | ✅ 成立 |
| 6 | 超时后 `compose` 不启动 | 事件序列 `digest→analyze→llm_start→llm_cancelled`，无 `compose` | ✅ 成立 |
| 7 | `response_format` 在最终 body **顶层** | MockTransport 捕获 | ✅ 成立 |
| 8 | `thinking` 在最终 body **顶层**（无 `extra_body` 包装层） | 同上 | ✅ 成立 |
| 9 | 不传 `temperature` 时 body 无该键 | 同上 | ✅ 成立 |
| 10 | `http_async_client` 是 1.7.2 合法字段 | `model_fields` + 构造 | ✅ 成立 |
| 11 | `ChatOpenAI(timeout=)` 单次尝试上限真的生效（即使注入了共享 client） | 真实 socket 服务器挂住不响应：`timeout=1.0` → 1.02s 抛 `OpenAITimeoutError` | ✅ 成立 |
| 12 | `SecretStr` 的 `repr` 不泄露 key | `repr(Settings)` | ✅ 成立 |
| 13 | `pytest-asyncio 0.24.0` 可用 | 导入 + 实跑异步用例 | ✅ 成立 |
| 14 | `backend` 在 `studyDays < 3` 时不发 HTTP | `AiServiceImpl.report` 读码 | ✅ 成立 |
| 15 | `backend` 的 `example` 降级只在**收到失败**时触发 | `AiServiceImpl.example` 读码 | ✅ 成立 |
| 16 | `.env` 被忽略、`.env.example` 不被忽略 | `git check-ignore -q` | ✅ 成立 |
| 17 | `.venv/` 被忽略 | `git check-ignore -v` → `.gitignore:23:.venv/` | ✅ 成立 |
| 18 | 模型名 `deepseek-flash` 为当前有效 id | 官方 changelog + `/models` 文档 | ✅ 成立 |

> 第 11 条值得单独强调：`ChatOpenAI` 的 `timeout` **会覆盖**共享 client 的 timeout
> （实测 `ChatOpenAI(timeout=99)` + `AsyncClient(timeout=1.0)` → 等了 99s 才超时）。
> 也就是说**共享 client 的默认 5s timeout 不会误伤长调用**，单次尝试上限完全由
> `ChatOpenAI(timeout=...)` 决定——这正是 `design.md §5` 第 2 点想要的效果。

---

## E. 需要改文档的清单（待用户确认后一次性改）

| 位置 | 改动 |
|---|---|
| `design.md §5` 代码块 + 要点 4/10 | `max_tokens` 移入 `extra_body`；说明理由（A1） |
| `design.md §4` 配置表 | `DEEPSEEK_API_KEY` 改为 `SecretStr \| None = None`，语义下移（A4） |
| `design.md §3` 结构清单 | 补 `pytest.ini`（`asyncio_mode = auto`）（A3） |
| `design.md §3` + §5 | `main.py` 明确为应用工厂，支持注入 HTTP client（B4） |
| `design.md §4/§5` | `LLM_ATTEMPT_TIMEOUT_S` 10 → 5，或补写重试被 deadline 截断的说明（B3） |
| `implement.md` 阶段 2 第 9 条 | `max_tokens` 走 `extra_body`（A1） |
| `implement.md` 阶段 5 第 17 条 | lifespan 测试改用 `lifespan_context`（A2） |
| `implement.md` 阶段 9 红线命令 | 排除红线测试自身与占位符（B1、B2） |
| `research/01` §6 | 已补入 `max_tokens` 改名、`http_async_client`、取消传播、图路由实测 |

**不改**：契约文档 `docs/接口详细设计.md`（§9 字段冻结）、backend 代码。
