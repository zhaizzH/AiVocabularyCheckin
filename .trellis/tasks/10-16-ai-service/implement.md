# ai-service 执行计划

> 有序清单 + 验证命令 + 评审闸口 + 回滚点。
> 需求见 `prd.md`，技术设计见 `design.md`，外部事实见 `research/01-deepseek-and-deps.md`。

---

## 0. 分支与提交策略

- 单开发者项目，**直接在 `main` 上按阶段提交**（与 backend 任务一致；git 历史是 S4 交付物的一部分）。
- 提交信息格式：`feat(ai-service): ...` / `test(ai-service): ...` / `chore(ai-service): ...`。
- **每阶段跑该阶段验证命令，通过才进入下一阶段**，每个阶段末尾一个提交（便于回滚定位）。
- 提交前必查：`git status` 中**不得**出现 `.env`，且不得把 `docs/` 那 24 项既有删除卷入。

## 1. 前置准备（阶段闸口：环境可复现、依赖闭包干净）

> **版本基线（用户指定）**：`langchain==1.3.18`（全家族 1.x，含 `langgraph 1.2.16`）。
> 已用隔离 venv 实测整套栈可安装、可导入、无数据库包（`research §1.2`）。

1. [x] 确认 Python 版本：`python --version` → 3.12.7
2. [x] 模型链路必须使用 `async def` 路由/链和 `ainvoke`/图 `ainvoke`；不得用同步 `invoke` 或线程池套 `invoke`，否则 asyncio 总 deadline 无法保证取消底层 HTTP 请求。
   - 通过测试证明 deadline 触发后 HTTP 请求任务已取消/关闭，且 report compose 不再启动。
3. [x] 创建 `ai-service/` 目录骨架（`app/` + `tests/` + 各 `__init__.py`）
   - **同时创建 `pytest.ini`**：`[pytest]` + `asyncio_mode = auto`。
     缺它则全部 async 用例被 skip 而退出码仍 0（`research §6.8`），闸口判据失效。
4. [x] `requirements.txt`：按 `design.md §10` 锁定全部直接依赖版本（含 `langchain`、`langgraph` 与 `pytest-asyncio`）
   - 依据：`langchain==1.3.18` 的闭包**已无 SQLAlchemy**（0.3.x 才有，见 `research §1.1`）；异步测试需使用锁定的 pytest-asyncio。
5. [x] `.env.example`（只含键名与占位说明，**无真实 key**）
6. [x] 推荐在 `ai-service/.venv` 安装依赖（`.venv/` 已 gitignore；避免污染全局）

**验证**：

```bash
cd ai-service
# ① 可用 venv（推荐）或全局环境
python -m venv .venv && .venv/Scripts/python.exe -m pip install -r requirements.txt   # Windows
# ② 导入链验证（LLM 栈 + 图引擎）
python -c "
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser
from langchain_core.runnables import RunnableLambda
from langchain_openai import ChatOpenAI
from langgraph.graph import StateGraph, END
print('OK: langchain-core / langchain-openai / langgraph 均可导入')
"
# ③ 依赖闭包不含数据库包（红线）
python -c "
import importlib.metadata as md
for pkg in ('langchain','langchain-core','langchain-openai','langgraph'):
    reqs = md.requires(pkg) or []
    bad = [r for r in reqs if any(k in r.lower() for k in ('sqlalchemy','mysql','psycopg','sqlite'))]
    assert not bad, (pkg, bad)
print('OK: 依赖闭包无数据库包')
try:
    md.version('SQLAlchemy'); print('WARN: 环境中存在 SQLAlchemy（需排查是否本任务引入）')
except Exception: print('OK: SQLAlchemy 不存在')
"
# ④ .env 必须被忽略；.env.example 必须不被忽略（quiet：0=被忽略）
git check-ignore -q ai-service/.env && echo "OK: .env 被忽略" || echo "FAIL"
git check-ignore -q ai-service/.env.example \
  && echo "FAIL: .env.example 被误忽略" || echo "OK: .env.example 可入库"
```

**实测证据（2026-10-17，隔离 venv）**：

| 检查 | 实测结果 |
|---|---|
| 安装 `langchain==1.3.18` + `langchain-openai==1.7.2` + FastAPI 栈 | EXIT=0，无冲突 ✅ |
| `langchain` 全闭包（39 包）逐名筛查数据库包 | **NONE** ✅ |
| 安装后 `SQLAlchemy` | **NOT INSTALLED** ✅ |
| `git check-ignore -q ai-service/.env` | exit **0** → 被忽略 ✅ |
| `git check-ignore -q ai-service/.env.example` | exit **1** → 未被忽略 ✅ |

异步 HTTP client 的注入、取消传播和 lifespan 资源关闭尚未在本机锁定依赖中验证；实现阶段须依 `test_llm_request.py` / `test_deadlines.py` / `test_lifespan.py` 实测，不把未验证项标为已通过。 

⚠️ **本机全局环境现状**：全局（Python 3.12.7）已装 `langchain 0.3.7` 与 `SQLAlchemy 2.1.4`，
**均非本任务所装**，而是既有环境（早期探测时曾用 `pip install langchain-openai==0.2.6` 拉入过 `openai 1.x`）。
**决定：本次改在隔离 venv 中验证与运行**（避免把全局 langchain 顶成 1.x 影响其他项目）。
**判据不是「全局有没有 SQLAlchemy」**（那是既成事实），而是
「`requirements.txt` 的**依赖闭包里**有没有」+「ai-service 代码有没有引用」。
后者由阶段 6 的 AST 测试机械保证。

---

## 2. 配置与日志（阶段闸口：配置单一入口、无硬编码密钥）

6. [x] `app/core/config.py`：`pydantic-settings` 的 `Settings`，字段按 `design.md §4`
   - `DEEPSEEK_API_KEY` 类型为 **`SecretStr | None = None`**（可缺失）——实测若写成 `SecretStr` 无默认值，
     `Settings()` 直接抛 `ValidationError`（**启动即崩**），与「启动不崩、调用时 503」矛盾（`research §2.5`）；
     且**不在任何日志/formatter 中输出**
   - 用 `SecretStr` 承载 key（避免 `repr(settings)` 意外打印明文）
7. [x] `app/core/logging.py`：按 spec 格式 `%(asctime)s %(levelname)s %(name)s %(message)s` 配一次
   - 用 `lifespan=` 上下文管理器形态，**不用** `@app.on_event`（starlette 0.41 下已弃用）
   - `app` 用**工厂**：`create_app(*, http_client=None) -> FastAPI`，模块级 `app = create_app()` 供 uvicorn 用；
     `http_client` 参数是离线捕获请求体的注入点（`design.md §3`）
8. [x] `app/core/errors.py`：`design.md §7.1` 的**稳定 detail 常量表**（ASCII 小写下划线）
9. [x] `app/core/llm.py`：`ChatOpenAI` 工厂
   - 参数字段名以 `research §2.5` 实测为准：`model` / `base_url` / `api_key` /
     `timeout` / `max_retries` / `extra_body` / `http_async_client`
   - 🔴 **`max_tokens` 必须放进 `extra_body`，不可作为构造参数**：
     `extra_body={"thinking": {"type": "disabled"}, "max_tokens": <per feature>}`。
     实测 `ChatOpenAI(max_tokens=512)` 会发出 `max_completion_tokens: 512`（DeepSeek 不认），
     只有 `extra_body` 路径才发出 `max_tokens`（`research §6.1`）
   - `DEEPSEEK_THINKING_ENABLED=true` 必须启动校验失败
   - lifespan 创建/关闭共享 `httpx.AsyncClient`，通过 `http_async_client` 传入 `ChatOpenAI`；依赖注入/惰性 provider 确保模型和图 Runnable 不会跨 lifespan 缓存已关闭的 client
   - 每个 feature 的模型用 `.bind(response_format={"type": "json_object"})` 绑定 JSON mode；report 的 analyze/compose 两步均使用绑定后的 Runnable
   - 实现 `tests/test_llm_request.py`：HTTP mock 捕获锁版最终 JSON body，验证顶层 `response_format` 与顶层 `thinking.type=disabled` 同时存在（SDK 将 ChatOpenAI `extra_body` 合并为供应商扩展字段；不得断言 body 中有 `extra_body` 包装层，不访问外网）
   - **不传 `temperature`**（thinking 模式不支持，见 `design.md §5`）
   - 只缓存绑定前的模型配置或在 lifespan 中构造模型/图；共享 `AsyncClient` 与生命周期绑定，不得用进程级 `lru_cache` 永久捕获 lifespan client。图实例可在同一 lifespan 内缓存。 

**验证**：
```bash
cd ai-service
python -c "
from app.core.config import get_settings
s = get_settings()
print('model=', s.deepseek_model, 'base=', s.deepseek_base_url)
assert 'sk-' not in repr(s), 'FAIL: settings repr 泄露 key 形状'
print('OK: settings repr 未泄露')
"
python -c "
from app.core.errors import ERR_LLM_TIMEOUT
assert ERR_LLM_TIMEOUT.isascii(), 'FAIL: detail 含非 ASCII'
print('OK:', ERR_LLM_TIMEOUT)
"
```
> `assert 'sk-' not in repr(s)` 是**关键断言**：若有人把 key 从 `SecretStr` 换成 `str`，
> 或给 `Settings` 加了会打印字段的 `__repr__`，这条会立刻失败。

---

## 3. Schema 层（阶段闸口：字段与契约 §9 逐字一致）

10. [x] `app/schemas/example.py` / `explain.py` / `report.py` / `common.py`
    - 响应字段名**逐字**：`example` / `exampleTranslation` / `mnemonic`；
      `explanation` / `advice`；`overview` / `weakness` / `suggestion`
    - 请求宽松度按 `design.md §2.2`（`context` 允许空串、`topMistakeWords` 默认 `[]`）
    - 用 `Field(default="")` 而非可变默认参数（spec 禁可变默认参数）

**验证**：
```bash
cd ai-service
python - <<'EOF'
from app.schemas.example import ExampleResponse
from app.schemas.explain import ExplainResponse
from app.schemas.report import ReportResponse
assert set(ExampleResponse.model_fields) == {"example","exampleTranslation","mnemonic"}
assert set(ExplainResponse.model_fields) == {"explanation","advice"}
assert set(ReportResponse.model_fields) == {"overview","weakness","suggestion"}
print("OK: 响应字段与契约 §9 逐字一致")
EOF
```

---

## 4. 提示词与解析（阶段闸口：解析容错矩阵全绿）

11. [x] `app/chains/prompts.py`：example、explain、report compose 三个模型提示词，**每个都必须含 "json" 字样 + 契约字段 JSON 示例**（DeepSeek JSON Output 的硬性要求，见 `research §2.3`）
    - report analyze 使用第四个内部分析提示词及专属 JSON 示例/schema；它不改变 HTTP 报告契约字段。
    - 例句：1 英文例句 + 中文翻译 + 1 条助记（中英混排助记按契约 §6.1 示例风格）
    - 讲解：围绕「为什么错 / 易混点」+ 学习建议；
      ⚠️ **不得假设 `wrongContext` 含用户误选项**（`design.md §2.1` 第 6 条）
    - 报告：分三段
12. [x] `app/chains/parsing.py`：`parse_json_object(text)` 四步容错（`design.md §6.4`）
13. [x] 字段收敛函数（白名单 + 补空 + `str()` + `strip`）

**验证**：
```bash
cd ai-service
python -m pytest tests/test_parsing.py -q     # §9.2 的解析矩阵
```

## 5. 三个 Chain（批次闸口：单测全绿，不真调 API）

14. [x] `app/chains/example_chain.py`：`prompt | json_llm | StrOutputParser()` → JSON 解析 → Response
15. [x] `app/chains/explain_chain.py`：同形态，使用绑定 JSON mode 的 Runnable
    - example / explain 在 router/service 层分别施加 12s / 25s 请求级总 deadline（含重试、解析）；ChatOpenAI timeout 单独约束单次模型请求尝试。超时映射 `ERR_LLM_TIMEOUT`，测试验证取消后不残留后台调用。
16. [x] `app/chains/report_graph.py`：**`langgraph.StateGraph` 多步编排**（`design.md §6.3`）
    - `ReportState = TypedDict(total=False)`；节点 `digest`（纯 Python，可单测）→ 条件路由 → `analyze` → `compose`
    - 当 `topMistakeWords=[]` 且 `masteryRate==100`，`digest` 直接产出完整谨慎措辞的 report 并路由 END；不调用 LLM
    - 其他数据走 `analyze` → `compose`；`analysis` 使用内部 Pydantic 结构化模型和 JSON mode，`compose` 入口断言 analysis 存在且非空
    - 三节点共享 state：`stats`（输入）→ `analysis`（LLM #1）→ `report`（digest 快速路径或 LLM #2）
    - 图/Runnable 的生命周期绑定 FastAPI lifespan；退出时清除依赖该 lifespan AsyncClient 的实例，不做跨 lifespan 的模块级缓存
    - report 图执行在 router/service 层使用 50s 总 deadline（含两次调用、重试、退避及解析）；单次 LLM 尝试 timeout 独立配置。超时必须取消后续节点并映射 `ERR_LLM_TIMEOUT`。不得在重试/进入 compose 后重置总 deadline；compose 仅能使用 deadline 剩余预算。
    - 单测断言常规路径 state 含 `analysis` 与 `report`、LLM 调用 2 次；快速路径 report 完整且调用 0 次；错误 route/state 不得静默返回空字段；慢 FakeLLM 验证总 deadline 与取消行为。 
17. [x] `tests/conftest.py`：异步 `FakeLLM` Runnable + async ASGI 测试 client fixture
    - Fake 用 `RunnableLambda` 的 async callable / async generator 或等效可取消 Runnable，按序返回 `AIMessage`；严禁用同步 Fake 模拟超时取消行为。
    - request 级测试可用 `httpx.AsyncClient` + `ASGITransport`；但那**不跑 lifespan**。
      lifespan 相关用例必须手动 `ctx = app.router.lifespan_context(app)` 进/出
      （实测裸 `ASGITransport` 下 lifespan 事件为 `[]`，`research §6.7`）。
    - lifespan 测试至少启动/关闭 app 两次，确认每轮新建 client、旧 client 已关闭，feature Runnable/report 图不复用旧 client；仅复用连接池/客户端/编译图，不得缓存请求或生成结果。

**验证**：
```bash
cd ai-service
python -m pytest tests/test_example.py tests/test_explain.py tests/test_report.py tests/test_deadlines.py tests/test_lifespan.py -q
```
要求：接口成功/失败、report 路由和快速路径、总 deadline 及取消行为、重复 lifespan 资源生命周期用例全绿。  

## 6. 路由与应用（阶段闸口：`/docs` 三路由可见、错误映射正确）

18. [x] `app/routers/ai.py`：三个 POST 路由（`APIRouter(prefix="/ai/v1")`）
    - 统一异常映射（`design.md §7.3`）：`HTTPException` 原样上抛 → 超时 504 → 其余 502
    - 每次调用打 INFO（功能名 + 耗时），失败打 WARN（**不记请求体**）
19. [x] `app/main.py`：应用入口 + `/health` + 路由注册 + 日志初始化
    - `/health` 返回 `{"status":"ok","llmConfigured": bool}`，**不含 key**
20. [x] 红线测试 `tests/test_no_database_imports.py`（AST 遍历 + requirements 断言）

**验证**：
```bash
cd ai-service
python -m pytest -q                       # 全部用例
python -c "
from fastapi.testclient import TestClient
from app.main import app
c = TestClient(app)
h = c.get('/health'); assert h.status_code == 200, h.text
assert 'sk-' not in h.text, 'FAIL: health 泄露 key'
spec = c.get('/openapi.json').json()
for p in ['/ai/v1/example','/ai/v1/explain','/ai/v1/report']:
    assert p in spec['paths'], p
print('OK: /health 无 key 泄露；三路由已在 OpenAPI 中')
"
```

---

## 7. 🔴 真实 key 路径（**本任务必做**，用户已确认 key 就绪）

21. [x] 取得 key 后写入 `ai-service/.env`（用户已同意此方式）
    - **写前**：`git check-ignore -v ai-service/.env` 必须命中（阶段 1 已验证）
    - **写后**：`git status --porcelain ai-service/` **不得**列出 `.env`
    - 全程**不打印** key 的值；`echo`/`cat` 一律不作用于 `.env`
22. [x] 启动服务：`cd ai-service && uvicorn app.main:app --host 127.0.0.1 --port 8000`
23. [x] 真实调用三接口（用 `curl` 或本地脚本），**逐个记录耗时**：
    - example：期望 ≤ 12s（backend 预算 15s）
    - explain：期望 ≤ 25s
    - report：期望 ≤ 50s（两步调用）
24. [x] 校验返回体是**严格契约字段**且正文非空、为中文
25. [x] 失败路径实测：临时用**错误 key** 调一次，断言得到 **非 2xx + 稳定 detail**
    （不得返回 200 + 半截文本）→ 跑完立即恢复正确 key

**验证含证据留存**（把真实响应与耗时写入 `research/02-live-verification.md`，
**不写入 key、不写入完整提示词**）。

## 8. 与 backend 端到端联调（**本任务必做**）

26. [x] 前置：确认 backend 与 MySQL 可用（`ai_vocab` 库，`word_book` 3 行 / `word` 22761 行）
27. [x] 启动 backend（`--spring.profiles.active=local`），确认 8080 可用
28. [x] 用 `g5_` 前缀测试用户走通：注册 → 学习 → 打卡 → 调 `/api/client/ai/example`
    → 断言 **`code:"0"` + `degraded:false` + 非空 `example`**
29. [x] `explain` 链路：造一条错题 → 调 `/api/client/ai/explain` → `degraded:false`
30. [x] `report` 链路：需 ≥3 天打卡数据（可用跨日脚本或直接造 `study_record`），
    调 `/api/client/ai/report` → `degraded:false` 且三段非空
31. [x] **反证**：停掉 ai-service，重跑同三条 → 必须回到 `degraded:true` / 5001 / 5002
    （证明「有 AI」与「无 AI」两条路径都真实成立，不是靠 mock）
32. [x] 清理测试数据（`g5_` 前缀），复查 `anime_tracker` 仍 20 表未动

**⚠️ 关键顺序陷阱**：`backend/scripts/g5-smoke.py` **刻意要求 `127.0.0.1:8000` 不可达**
（`smoke-test.sh` 会前置断言并 `exit 2`）。所以：
- 跑 `g5-smoke.py` / `smoke-test.sh` 前**必须停掉 ai-service**；
- 跑本阶段的真实联调时**必须启动 ai-service**。
两者互斥，绝不可同时跑。

## 9. 文档与收尾（阶段闸口：README 可复现、红线可验证）

33. [x] `ai-service/README.md`：环境变量表（说明 `DEEPSEEK_THINKING_ENABLED` 固定关闭且 true 配置会启动失败）、异步服务启动命令、三接口 curl 示例、
    **与 backend 的契约对接说明**、模型名退役提示（附官方文档链接）
34. [x] 更新 `handoff.md`：ai-service 状态由「未开工」改为「已完成」，
    并写明需不需要 key 的两种验证范围
35. [x] 红线自查（**逐条留证据**）：

```bash
cd /c/workspace/class/AiVocabularyCheckin
# ① 代码层无数据库痕迹（排除红线测试自身：它按设计必须包含这些禁用词）
grep -rniE "mysql|pymysql|psycopg|sqlalchemy|sqlite|database" \
  ai-service/app ai-service/requirements.txt \
  && echo "FAIL: 代码与依赖清单命中数据库词，逐条排查" || echo "OK: 代码与依赖清单无数据库痕迹"
# 红线测试文件单独确认：它只含字面量清单，不得有真实驱动 import
grep -rnE "^\s*(import|from)\s+(mysql|pymysql|psycopg2?|sqlalchemy|sqlite3)" ai-service/ \
  && echo "FAIL: 出现真实驱动 import" || echo "OK: 无驱动 import"
# ② 仓库内无真实 key 泄漏（排除模板与测试假 key）
grep -rn "sk-" ai-service/ --exclude-dir=__pycache__ --exclude=.env.example --exclude-dir=tests \
  && echo "FAIL: 命中 sk- 字面量" || echo "OK: 无 key 字面量"
# ③ .env 未被跟踪
git ls-files ai-service/ | grep -c "\.env$" ; echo "上值必须为 0"
# ④ 无 print() 调用
grep -rn "print(" ai-service/app && echo "FAIL" || echo "OK: app/ 无 print"
```

> ① 原命令会**自我命中** `tests/test_no_database_imports.py`（该文件按设计必须包含禁用词清单），
> 导致闸口永远无法通过；故扫描范围限定为 `app/` 与 `requirements.txt`，
> 并用「真实 import 检查」补上全目录覆盖。
> ② `.env.example` 的占位值**不使用** `sk-` 前缀（用 `your-deepseek-api-key-here`），
> 测试假 key 集中在 `tests/` 下，一并排除。

36. [x] 提交（按阶段粒度，见 §0），确认 `git show --stat` 中无 `.env`、无 `docs/` 既有删除

---

## 10. 验证命令速查

```bash
# —— 全量单测（不真调 API，秒级）——
cd ai-service && python -m pytest -q

# —— 起服务 ——
cd ai-service && uvicorn app.main:app --host 127.0.0.1 --port 8000

# —— 真实调用（key 已在 .env）——
curl -s -X POST http://127.0.0.1:8000/ai/v1/example \
  -H 'Content-Type: application/json' \
  -d '{"word":"abandon","translation":"v. 放弃","context":"CET4"}'

# —— 真实路径测试（显式开启）——
cd ai-service && DEEPSEEK_LIVE_TEST=1 python -m pytest tests/test_live_deepseek.py -q -s

# —— backend 联调（注意先停 ai-service 才能跑 g5）——
DBPASS=<pw> bash backend/scripts/smoke-test.sh     # 需要 8000 不可达
```

---

## 11. 回滚点

| 回滚点 | 触发情形 | 动作 |
|---|---|---|
| R1（阶段 1 后） | 依赖闭包不干净 | 删除 `ai-service/`，回到「无 AI 服务」状态 |
| R2（阶段 6 后） | 单测无法收敛 | 同上；backend 降级路径不受影响 |
| R3（阶段 7-8 后） | 真实链路不可用 | **保留代码 + README 标注**（可复现的 mock 单测仍有价值），向用户报告阻塞 |
| R4（提交后） | 发现契约偏差 | 契约先行：先回写 `docs/接口详细设计.md` §10 并确认，再改代码 |

**回滚安全性论证**：本任务**只新增 `ai-service/` 目录**，不改任何既有文件
（`.gitignore` 实测已覆盖 `.env`，无需改）。删除该目录后，backend 立即回到
「AI 不可用 → 降级」路径，系统仍可完整演示 —— 这是架构解耦的直接收益。

---

## 12. 已知陷阱（写下来避免重踩）

| # | 陷阱 | 规避 |
|---|---|---|
| 1 | ~~`langchain` 元包带入 SQLAlchemy~~ **已推翻**：1.3.18 闭包无 SQLAlchemy（0.3.x 才有，实测） | 按用户要求正常装 `langchain` + `langgraph`（`research §1.1`） |
| 2 | `deepseek-chat` 已退役 | 用 `deepseek-flash`，且做成 env 可覆盖 |
| 3 | thinking 默认开启 + 与 JSON 输出冲突（空 content） | 显式 `thinking: disabled` |
| 4 | JSON 模式未含 "json" 字样 → 模型空转至 token 耗尽 | 提示词强制含 "json" + 示例 + 设 `max_tokens` |
| 5 | GBK 终端下 `grep -c` 统计中文会数错 | 用 Python 统计；detail 常量全 ASCII |
| 6 | U+2212 等特殊符号在 GBK 控制台报错 | 代码/脚本内只用 ASCII 符号 |
| 6b | 全局环境是旧的 `langchain 0.3.7`，装上 1.x 会顶掉它（影响其它项目） | 用隔离 venv（`ai-service/.venv`，已 gitignore）；**不要**在全局环境跑 `pip install -r` |
| 6c | 旧教程/旧文档的 0.2/0.3 写法与 1.x 不兼容 | 只依赖稳定层（`ChatPromptTemplate` / LCEL / `ChatOpenAI` / `StateGraph`），已逐项实测 |
| 6d | `inspect.signature(ChatOpenAI.__init__)` 看不到 `base_url`/`timeout`/`max_tokens`/`extra_body` | 不要用签名探测判断支持性；用**实际构造**验证（已实测可用） |
| 6e | `langgraph` 的 `TypedDict(total=False)` 下节点若返回错键会静默丢数据 | 节点返回完整增量；单测断言 `analysis` 与 `report` 均在 state |
| 7 | `g5-smoke.py` 与 ai-service 互斥 | 见阶段 8 的顺序陷阱 |
| 8 | FakeLLM 写普通类无法接入 LCEL | 用 `RunnableLambda(...).with_config(run_name=...)`（本机已验证） |
| 9 | 单测若用真实网络 → 慢且不稳定 | 全部替换 LLM；真实调用单独放 `test_live_deepseek.py` 并默认 skip |
| 10 | 改动全局 Python 环境影响其它项目 | 已在 prd/research 披露；README 提供 venv 复现方式 |
| 11 | `max_tokens` 作为构造参数被 SDK 改名为 `max_completion_tokens`，DeepSeek 忽略 → JSON 被截断 | 一律经 `extra_body` 传（`research §6.1`） |
| 12 | 裸 `ASGITransport` 不跑 lifespan → `test_lifespan.py` 假绿 | 手动 `app.router.lifespan_context(app)`（`research §6.7`） |
| 13 | 缺 `asyncio_mode = auto` → 全部 async 用例被 skip、退出码仍 0 | `pytest.ini` 纳入交付物（`research §6.8`） |
| 14 | 用 `MockTransport` 测超时会得出错误结论（它不实现超时） | 超时必须用真实 socket 验证（`research §6.6`） |
| 15 | `@app.on_event` 在 starlette 0.41 已弃用 | 用 `lifespan=` 上下文管理器 |
| 11 | `git check-ignore -q` 在 Windows Git Bash 下实测可用（0=忽略/1=未忽略） | 已实测，见阶段 1 证据表 |
