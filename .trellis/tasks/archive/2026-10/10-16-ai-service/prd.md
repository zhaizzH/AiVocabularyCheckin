# ai-service AI 微服务（FastAPI + LangChain + DeepSeek）

## Goal

实现无状态 AI 微服务，为 backend 提供三个生成接口：例句生成、错题讲解、学习报告。**本服务是 backend AI 功能的唯一依赖，其不可用时 backend 必须能降级运行。**

## 依赖与顺序

- **契约字段**必须与 `docs/接口详细设计.md` §9 严格一致（backend 按该契约解析响应）。
- 与 `10-16-backend-api` **无代码依赖**，可并行；但 backend 的降级路径不依赖本服务，而本服务的成功路径依赖 AI key。
- ✅ **DeepSeek key 已就绪（2026-10-17 用户确认）**：本任务**包含真实生成路径验收**，不再只做到「代码就绪 + stub 单测」。
  原「无 key 只能到 stub」的降级方案**保留为兜底**（key 失效/额度耗尽时仍可复现单测基线）。

## 规划期新增事实（研究所得，证据见 `research/01-deepseek-and-deps.md`）

以下几条**改变实现方式**，需在实现中落实：

0. **版本基线（用户指定）**：`langchain==1.3.18`，全家族 1.x，且 `langgraph` 为必需依赖
   （`langchain 1.3.18` 要求 `langgraph>=1.2.11,<1.3.0`）。`langgraph` **仅用于 report 的多步编排**。
1. **依赖闭包不得含 SQLAlchemy（红线）——但 1.x 天然满足**：
   实测 `langchain==1.3.18` 的完整闭包（39 个包）**无任何数据库包**；
   依赖 SQLAlchemy 的是 **0.3.x 的旧版元包**（`SQLAlchemy>=1.4,<3`）。
   → 因此**可以正常安装 `langchain` 元包**（符合用户要求），红线仍成立。
   验证方式：`importlib.metadata.requires()` 逐包筛查 + 隔离 venv 安装后确认 `SQLAlchemy` 不存在。
2. **模型名**：`deepseek-chat` / `deepseek-reasoner` 已于 2026-07-24 退役；当前官方 id 为
   `deepseek-flash`（推荐）/ `deepseek-v4-pro`（淘汰中）。→ 默认 `deepseek-flash`，环境变量可覆盖。
3. **思考模式默认开启且与 JSON 输出冲突**：官方文档载明 thinking 默认 `enabled`+`high`，
   且不支持 `temperature`；社区报告 `json_object` + thinking 会返回**空 `content`**。
   → 三接口**统一关闭思考**（`extra_body={"thinking":{"type":"disabled"}}`），
   不提供开启思考模式的覆盖开关：JSON mode 与 thinking 已知冲突，任何环境配置均不得启用 thinking。
   理由：backend 超时预算 15/30/60s，思考既增延迟又触发已知缺陷，而本任务是短文本结构化生成。

另需注意 backend 侧的**既有实现口径**（读码所得，非契约原文）：

4. `context` 传的是词书 category，**可能为空串**；`wrongContext` 实际形如
   `"该词在复习中答错，累计错误 3 次"`，**不含「用户误选了哪个选项」**
   （backend 不记录选项数据）→ 讲解提示词不得假设能拿到用户所选的错误项。
5. backend 认为「HTTP 200 + 可反序列化 JSON」即成功；非 2xx 或响应体不可解析
   一律映射 5002。故**失败必须以非 2xx + 稳定 detail 返回**，不要返回 200 + 半截内容。

## 需求

### JSON 输出及思考模式

- 三个接口均通过 OpenAI 兼容请求的 `response_format={"type":"json_object"}` 启用 JSON mode；提示词继续包含 JSON 字样和输出示例，解析容错与 Pydantic 字段校验不得移除。
- 所有模型请求显式关闭 DeepSeek thinking。`DEEPSEEK_THINKING_ENABLED` 不作为可覆盖开关；若保留该配置名兼容旧 `.env`，值为 `true` 必须启动校验失败，不得静默忽略。
- 自动化验证最终 HTTP 请求体：顶层 `response_format` 与顶层 `thinking.type="disabled"` 同时存在。

### 1. 工程与硬约束

- `ai-service/` 独立 Python 工程：FastAPI + LangChain + `pydantic-settings` + `httpx`/`openai`（OpenAI 兼容）。
- **硬约束（架构红线）：不引入任何数据库驱动，不建立任何 MySQL 连接。** 所需数据全部由 backend 在请求体中传入。
- 大模型密钥仅从环境变量读取（`.env`，**不入库不入仓**）；提供 `.env.example` 模板。
- 无状态：不写本地文件、不缓存请求/响应/生成结果、不存会话；允许复用进程内 HTTP 连接池、无状态客户端和当前 lifespan 编译的 graph。
- `requirements.txt` **必须锁版本**（LangChain 迭代快，规范明确要求）。

### 2. 三个接口（详见契约 §9）

| 接口 | 请求 | 响应 | 超时（由 backend 控制） |
|---|---|---|---|
| `POST /ai/v1/example` | `{word, translation, context}` | `{example, exampleTranslation, mnemonic}` | 15s |
| `POST /ai/v1/explain` | `{word, translation, wrongContext}` | `{explanation, advice}` | 30s |
| `POST /ai/v1/report` | `{studyStats:{studyDays,totalLearned,checkInRate,topMistakeWords,masteryRate}}` | `{overview, weakness, suggestion}` | 60s |

- 全部 POST + JSON；每个路由必须有**类型化 Pydantic 请求/响应模型**（不接受裸 `dict`）。
- 失败抛 `HTTPException`，`detail` 使用**稳定字符串**（backend 会映射为 5001/5002）。
- 学习报告按 LangChain **多步编排**（整理数据 → 条件路由：无明显薄弱错题则确定性直出，否则分析 → 生成），契合 S3 §3.5「多步 Chain」的设计意图。快速路径条件为 `topMistakeWords=[]` 且 `masteryRate=100`，不调用 LLM，仍返回契约要求的三段非空字段；不能夸大为用户已掌握全部学习内容。学习天数不足仍由 backend 前置拦截（<3 天不发 AI 请求）。
- 每个接口的服务端生成/解析都有小于 backend 超时的请求级总 deadline：example/explain/report 为 12/25/50 秒；模型 HTTP 单次尝试 timeout 独立设置；采用异步、可取消链路，超时后不得残留后台模型调用。

### 3. 目录结构（S3 §3.5 指定）

```
ai-service/
├── app/
│   ├── main.py         # FastAPI 入口、路由注册、日志配置
│   ├── core/           # 配置(pydantic-settings)、LLM 客户端工厂、日志
│   ├── chains/         # 例句 Chain、讲解 Chain、报告 Chain + 提示词模板
│   ├── schemas/        # Pydantic 请求/响应模型
│   └── routers/        # example.py / explain.py / report.py（或按功能命名）
├── tests/
├── pytest.ini          # asyncio_mode = auto（全部用例为 async，缺此配置会被静默 skip）
├── requirements.txt
└── .env.example
```

### 4. 提示词工程要求

- example、explain、report compose 的提示词模板独立成文件/常量，便于调整；report analyze 使用独立的内部分析模板，不改变 HTTP 契约。
- 输出必须**可结构化解析**：要求模型返回 JSON，并做解析容错（解析失败 → 抛 HTTPException，不返回半截文本）。
- 例句功能需输出：1 个英文例句 + 中文翻译 + 1 条助记提示（契约 §6.1 的 `mnemonic`）。
- 讲解功能需体现「为什么错 / 易混点」+ 学习建议。
- 报告需分三段：`overview` / `weakness` / `suggestion`。
- 语言：面向中文用户，解释文本用中文。

## 验收标准

### A. 工程与静态检查（不依赖 key）

- [ ] `uvicorn app.main:app` 可启动，`/docs` 可访问，三个路由可见。
- [ ] 三个路由均有 Pydantic 请求/响应模型，OpenAPI schema 正确；响应字段名与契约 §9 逐字一致。
- [ ] 每个路由**至少一个 pytest** 覆盖成功路径（**mock 大模型**，不真调 API）。
- [ ] 每个路由至少一个 pytest 覆盖失败路径（大模型异常 → 抛 HTTPException 且 detail 稳定）。
- [ ] `requirements.txt` 全部锁版本；`pip install -r` 可完成。
- [ ] `pytest.ini` 含 `asyncio_mode = auto`；自动化测试在 lifespan 真实运行的前提下验证资源生命周期（不得用裸 `ASGITransport` 冒充）。
- [ ] **红线：`ai-service/` 中不存在 mysql / sqlalchemy / pymysql / psycopg / database 相关代码或依赖**——
      且需额外证明**依赖闭包里也没有**（方式：`importlib.metadata.requires()` 逐包筛查
      `langchain`/`langchain-core`/`langchain-openai`/`langgraph`；隔离 venv 安装后确认 `SQLAlchemy` 不存在。
      本机全局环境已存在 SQLAlchemy，那是既有环境事实，不是本任务的引入——判据见 `implement.md` 阶段 1 证据表）。
- [ ] `.env.example` 存在且不含真实 key；`.env` 被 gitignore。
- [ ] 大模型调用有重试策略（有限次，避开冻结边界），失败最终抛 HTTPException。
- [ ] 三接口均启用 JSON mode；自动化 HTTP mock 确认最终请求体同时包含顶层 `response_format={"type":"json_object"}` 和顶层 `thinking.type="disabled"`。
- [ ] 自动化测试验证 example/explain/report 请求级总 deadline、异步取消传播与无后台调用遗留；report 条件快速路径调用 LLM 0 次、常规路径调用 2 次。
- [ ] 有 `README.md`：环境变量说明、启动命令、与 backend 的契约对接说明。

### B. 真实生成路径（key 已就绪，**纳入本任务验收**）

- [ ] 三个接口真实调用 DeepSeek 成功，返回结构符合契约（含中文正文、非空字段）。
- [ ] 与 backend 联调：`/api/client/ai/example` 返回 `degraded:false`。
- [ ] 记录单次调用耗时，确认在 15/30/60s 超时内（含是否可稳定落在 15s 内）。
- [ ] key 未配置/无效时的**失败路径**仍返回稳定 detail 与非 2xx（供 backend 判 5002）。

### C. 与 backend 的契约对齐（端到端）

- [ ] `explain` 接口在 `wrongContext` 为「该词在复习中答错，累计错误 N 次」这类输入下
      仍能产出可读中文讲解（不依赖不存在的「用户误选项」信息）。
- [ ] `report` 接口在 `topMistakeWords` 为空数组时不报错；若同时 `masteryRate == 100`，走确定性快速路径（不调用 LLM），返回三段非空且不夸大掌握度的契约响应。空数组但掌握度非 100 时仍走常规分析路径。
- [ ] `example` 接口在 `context` 为**空串**时不报错。

## 非目标

- 不做任何数据库访问（红线）。
- 不做鉴权（backend 到 ai-service 是内网调用，契约 §9 未定义鉴权；如需可后续加共享密钥）。
- 不做业务结果/响应缓存（契约 §6.3 明确报告不缓存）；HTTP 连接池、模型客户端和编译后的图属于进程内资源复用，不缓存请求或生成结果。
- **不改 backend 代码**：若发现契约/实现不一致，先回写 `docs/接口详细设计.md`（契约先行），
  再评估是否改 backend；本任务的边界是「按现有契约把 ai-service 做对」。
- **不改 `docs/` 契约字段**：契约 §9 三个接口的字段已冻结，本任务只需实现，不需变更。
  （供应商现状说明如需补记，另行确认。）
