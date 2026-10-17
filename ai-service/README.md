# ai-service

AI 微服务：例句生成 / 错题讲解 / 学习报告。FastAPI + LangChain（`langchain` 1.3.18 + `langgraph`）+ DeepSeek。

本服务**只管「调用大模型并返回结构化 JSON」**，不做降级、不做业务决策、不碰数据库。
降级策略、重试预算、错误码映射全部由 backend 负责（见契约 §9）。

## 快速开始

```bash
cd ai-service
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt   # Windows
cp .env.example .env                                          # 填入 DEEPSEEK_API_KEY
.venv/Scripts/python.exe -m uvicorn app.main:app --port 8000
```

启动后可访问：

- `GET /health` → `{"status":"ok","llmConfigured":true|false}`（不回显 key）
- `GET /docs` / `GET /openapi.json`

**注意**：`DEEPSEEK_API_KEY` 缺失时服务仍能启动（`llmConfigured=false`），
但三个 AI 接口会返回 `503 ai_service_llm_not_configured`。这是刻意的：
让容器能起来、健康检查能过，运维在日志里能直接看到「没配 key」，
而不是进程崩溃后反复重启。

## 接口

前缀 `/ai/v1`，全部 `POST`，响应字段严格对齐契约 §9。

| 路径 | 请求 | 响应 | 自控 deadline |
| --- | --- | --- | --- |
| `/example` | `word`, `translation`, `context` | `example`, `exampleTranslation`, `mnemonic` | 12s |
| `/explain` | `word`, `translation`, `wrongContext` | `explanation`, `advice` | 25s |
| `/report` | `studyStats{...}` | `overview`, `weakness`, `suggestion` | 50s |

三个 deadline 均严格小于 backend 的 15 / 30 / 60s：一旦 backend 先超时，
我们继续重试只是白烧 token。

### 错误约定

失败一律返回非 2xx + 稳定 ASCII `detail`，backend 据此判 `5001/5002`：

| HTTP | detail | 含义 |
| --- | --- | --- |
| 504 | `ai_service_timeout` | 请求级 deadline 到期 |
| 502 | `ai_service_llm_failed` | 供应商失败 / 网络错误 |
| 502 | `ai_service_bad_model_output` | 输出不是可解析的 JSON 对象 |
| 502 | `ai_service_empty_result` | 必填字段为空（无意义的成功） |
| 503 | `ai_service_llm_not_configured` | 缺 `DEEPSEEK_API_KEY` |
| 500 | `ai_service_internal_state` | 编排状态异常（不该发生） |

**绝不**返回 200 + 半截内容：backend 的空值兜底只在「收到失败」时触发，
假成功会直接污染用户数据。

## 实现要点（踩过的坑）

1. **`max_tokens` 必须经 `extra_body` 传**。`langchain-openai 1.7.2` 会把构造参数
   `max_tokens=` 改名成 `max_completion_tokens`，DeepSeek 不认这个键；而 JSON 模式下
   `max_tokens` 是唯一的防截断杠杆（截断会产出非法 JSON）。最终请求体顶层键为
   `max_tokens / messages / model / response_format / stream / thinking`。
2. **思考模式全接口关闭**（`extra_body={"thinking":{"type":"disabled"}}`）。
   `DEEPSEEK_THINKING_ENABLED=true` 会在启动校验阶段直接失败，不给线上留开关。
3. **不传 `temperature`**：与关闭思考模式配套，避免无意义的参数假象。
4. **JSON 模式**：`response_format={"type":"json_object"}` + 提示词含 `json` 字样与示例。
5. **报告用 `langgraph`**（digest → analyze → compose，digest 后条件路由），
   例句/讲解保持单步 LCEL——1 节点 1 边的图只是仪式性复杂度。
6. **报告快速路径**：`topMistakeWords` 为空**且** `masteryRate == 100` 时，
   直接用 Python 产出中性文案，0 次模型调用。该分支实际命中「从未答错过」的用户。
   文案刻意写成「暂无明显薄弱错题」，不提「已掌握全部内容」。
7. **`httpx.AsyncClient` 只在 lifespan 内创建/关闭**；`create_app` 是工厂，
   以便测试注入 `MockTransport` 客户端来离线捕获真实请求体。

## 测试

```bash
.venv/Scripts/python.exe -m pytest -q
```

全部用例秒级跑完、不访问外网。要求的覆盖点：

- 契约字段名与三个接口的成功/失败路径；
- **最终 HTTP 请求体**（MockTransport 离线断言 `response_format` / `thinking` / `max_tokens`）；
- deadline 真实取消在飞到模型边界（慢 Runnable 收到 `CancelledError`），
  且 report 超时后 `compose` 不被启动；
- lifespan 两轮启停：client 每轮新建、退出即 `aclose()`、引用被清空；
  （`ASGITransport` **不跑 lifespan**，故用 `app.router.lifespan_context` 手动驱动）
- 解析容错四步（裸 JSON / 代码围栏 / 前后夹文本 / 花括号配平）；
- **架构红线**：AST 扫描 `app/**/*.py` 禁止任何数据库驱动 import，
  并校验 `requirements.txt` 与真实安装闭包中无 DB 包。

`pytest.ini` 里的 `asyncio_mode = auto` 是**必需的**：没有它，全部 async 用例会被
静默 skip 且进程退出码仍为 0——那是「假绿」，比红灯更危险。

## 目录

```
ai-service/
├── app/
│   ├── main.py                 # create_app 工厂 + lifespan
│   ├── core/{config,logging,llm,errors}.py
│   ├── schemas/{common,example,explain,report}.py
│   ├── chains/{prompts,parsing,example_chain,explain_chain,report_graph}.py
│   └── routers/ai.py           # 三个路由 + 统一异常映射
├── tests/
├── requirements.txt
├── pytest.ini
├── .env.example
└── README.md
```

## 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | 空 | 缺失则接口 503；用 `SecretStr` 持有，`repr` 不泄露 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | 不带 `/v1` |
| `DEEPSEEK_MODEL` | `deepseek-flash` | 可覆盖 |
| `DEEPSEEK_THINKING_ENABLED` | `false` | 设为 `true` 会启动失败（刻意） |
| `LLM_ATTEMPT_TIMEOUT_S` | `5` | 单次尝试；10s×3 次会超出 example 的 12s 预算 |
| `EXAMPLE_TIMEOUT_S` | `12` | < backend 15s |
| `EXPLAIN_TIMEOUT_S` | `25` | < backend 30s |
| `REPORT_TIMEOUT_S` | `50` | < backend 60s |
| `LOG_LEVEL` | `INFO` | |

日志格式 `%(asctime)s %(levelname)s %(name)s %(message)s`。
**不记录** API key、请求体、`wrongContext`、提示词全文。

## 不做什么

- **不连数据库**：红线。降级/缓存/统计都在 backend。
- **不做隐式降级**：失败就报错，由 backend 决定是否用本地兜底。
- **不重试到超预算**：`max_retries=2` 但受请求级 deadline 约束。
- **不猜模型输出**：解析失败直接 502，不做「正则抠字段」「再问一次让它修」。
