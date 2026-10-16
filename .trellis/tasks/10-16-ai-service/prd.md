# ai-service AI 微服务（FastAPI + LangChain + DeepSeek）

## Goal

实现无状态 AI 微服务，为 backend 提供三个生成接口：例句生成、错题讲解、学习报告。**本服务是 backend AI 功能的唯一依赖，其不可用时 backend 必须能降级运行。**

## 依赖与顺序

- **契约字段**必须与 `docs/接口详细设计.md` §9 严格一致（backend 按该契约解析响应）。
- 与 `10-16-backend-api` **无代码依赖**，可并行；但 backend 的降级路径不依赖本服务，而本服务的成功路径依赖 AI key。
- ⚠️ **无 DeepSeek key 时**：本任务只能完成到「代码就绪 + 单测通过（stub 大模型）」，真实生成能力待 key 到位后再验。

## 需求

### 1. 工程与硬约束

- `ai-service/` 独立 Python 工程：FastAPI + LangChain + `pydantic-settings` + `httpx`/`openai`（OpenAI 兼容）。
- **硬约束（架构红线）：不引入任何数据库驱动，不建立任何 MySQL 连接。** 所需数据全部由 backend 在请求体中传入。
- 大模型密钥仅从环境变量读取（`.env`，**不入库不入仓**）；提供 `.env.example` 模板。
- 无状态：不写本地文件、不建缓存、不存会话。
- `requirements.txt` **必须锁版本**（LangChain 迭代快，规范明确要求）。

### 2. 三个接口（详见契约 §9）

| 接口 | 请求 | 响应 | 超时（由 backend 控制） |
|---|---|---|---|
| `POST /ai/v1/example` | `{word, translation, context}` | `{example, exampleTranslation, mnemonic}` | 15s |
| `POST /ai/v1/explain` | `{word, translation, wrongContext}` | `{explanation, advice}` | 30s |
| `POST /ai/v1/report` | `{studyStats:{studyDays,totalLearned,checkInRate,topMistakeWords,masteryRate}}` | `{overview, weakness, suggestion}` | 60s |

- 全部 POST + JSON；每个路由必须有**类型化 Pydantic 请求/响应模型**（不接受裸 `dict`）。
- 失败抛 `HTTPException`，`detail` 使用**稳定字符串**（backend 会映射为 5001/5002）。
- 学习报告按 LangChain **多步编排**（拉取/整理数据 → 分析 → 生成），契合 S3 §3.5「多步 Chain」的设计意图。

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
├── requirements.txt
└── .env.example
```

### 4. 提示词工程要求

- 三个功能的提示词模板独立成文件/常量，便于调整。
- 输出必须**可结构化解析**：要求模型返回 JSON，并做解析容错（解析失败 → 抛 HTTPException，不返回半截文本）。
- 例句功能需输出：1 个英文例句 + 中文翻译 + 1 条助记提示（契约 §6.1 的 `mnemonic`）。
- 讲解功能需体现「为什么错 / 易混点」+ 学习建议。
- 报告需分三段：`overview` / `weakness` / `suggestion`。
- 语言：面向中文用户，解释文本用中文。

## 验收标准

- [ ] `uvicorn app.main:app` 可启动，`/docs` 可访问，三个路由可见。
- [ ] 三个路由均有 Pydantic 请求/响应模型，OpenAPI schema 正确。
- [ ] 每个路由**至少一个 pytest** 覆盖成功路径（**mock 大模型**，不真调 API）。
- [ ] 每个路由至少一个 pytest 覆盖失败路径（大模型异常 → 抛 HTTPException 且 detail 稳定）。
- [ ] `requirements.txt` 全部锁版本；`pip install -r` 可完成。
- [ ] **grep 验证：`ai-service/` 中不存在 mysql / sqlalchemy / pymysql / psycopg / database 相关代码或依赖。**
- [ ] `.env.example` 存在且不含真实 key；`.env` 被 gitignore。
- [ ] 大模型调用有重试策略（有限次，避开冻结边界），失败最终抛 HTTPException。
- [ ] 有 `README.md`：环境变量说明、启动命令、与 backend 的契约对接说明。

## 真实 key 到位后的额外验收

- [ ] 三个接口真实调用 DeepSeek 成功，返回结构符合契约。
- [ ] 与 backend 联调：`/api/client/ai/example` 返回 `degraded:false`。
- [ ] 记录单次调用耗时，确认在 15/30/60s 超时内。

## 非目标

- 不做任何数据库访问（红线）。
- 不做鉴权（backend 到 ai-service 是内网调用，契约 §9 未定义鉴权；如需可后续加共享密钥）。
- 不做缓存（契约 §6.3 明确报告不缓存）。
