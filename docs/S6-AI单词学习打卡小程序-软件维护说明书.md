# S6 软件维护说明书

> 项目：AI 单词学习打卡系统（AiVocabCheckin）· 第 N 组
> 读者：运维/接手开发。目标：让不看源码的人也能完成日常维护与常见故障处理。
> 版本：V1.0 · 2026-10-17

## 1. 系统组成与运行拓扑

```
小程序（学生端）        管理端（浏览器）
        │ REST               │ REST
        └───────┬────────────┘
                ▼
   backend（Spring Boot，8080）——唯一连接数据库的服务
        │ MySQL 协议              │ HTTP（内网）
        ▼                         ▼
   MySQL（3306，库 ai_vocab）   ai-service（FastAPI，8000）→ DeepSeek API
```

**关键事实**：`ai-service` 无状态、不连数据库，宕机不影响核心学习功能（降级运行）；它是唯一持有 AI 密钥的进程。

## 2. 目录与模块速查

| 目录 | 职责 | 关键入口 |
|---|---|---|
| `backend/src/main/java/com/vocab/` | 业务后端 | controller/ service/ mapper/ entity/ dto/ common/ config/ |
| `backend/src/main/resources/db/` | SQL 迁移脚本 | V001__init.sql、V002__word_progress.sql（**新增脚本递增编号，不改旧脚本**） |
| `ai-service/app/` | AI 微服务 | routers/ai.py、chains/（prompts.py 提示词在此调整）、core/config.py |
| `miniprogram/` | 学生端 | pages/、services/、utils/request.js（唯一网络出口） |
| `frontend/src/` | 管理端 | pages/、services/、utils/request.ts（唯一网络出口） |

## 3. 配置维护

### 3.1 配置位置（均不入仓）

| 配置 | 文件 | 修改后 |
|---|---|---|
| MySQL 连接 / JWT 密钥 / 微信 AppID | `backend/src/main/resources/application-local.yml` | 重启 backend |
| DeepSeek key / 模型名 / 超时 | `ai-service/.env` | 重启 ai-service |

### 3.2 常见配置变更

| 场景 | 操作 |
|---|---|
| 更换 AI 模型 | 改 `.env` 的 `DEEPSEEK_MODEL` → 重启 ai-service → 跑 `scripts/verify_live.py` 验证。**模型名会退役**（deepseek-chat 已于 2026-07 退役），AI 接口突现 502 先查这里 |
| 轮换 DeepSeek key | 改 `.env` 的 `DEEPSEEK_API_KEY` → 重启；无需动 backend |
| 轮换 JWT 密钥 | 改 `jwt.secret` → 重启 backend；**全部用户需重新登录**（旧 token 立即失效），提前公告 |
| 调整每日新词量 | `StudyService` 中常量 `DAILY_NEW_WORDS`（默认 20），改后需同步测试断言 |

## 4. 日常维护操作

### 4.1 词库维护

- **新增词书**：管理端「词库管理 → 导入词库」上传 JSONL（字段格式见《接口详细设计》§8.1），坏行自动跳过并计数
- **单词增删改**：词书「查看单词」弹窗内操作；删除为逻辑删除
- **批量纠错**：直接在 MySQL 执行 UPDATE 后，无需重启（后端无词库缓存）

### 4.2 用户维护

- 禁用/启用：管理端「用户管理」；禁用即时生效（该用户下次请求即 401/1003）
- 提权管理员：`python scripts/create-admin.py --promote <username>`

### 4.3 数据备份

```bash
mysqldump -uroot -p ai_vocab > backup_$(date +%F).sql   # 建议每日定时执行
```

清理冒烟测试遗留数据：`python backend/scripts/clean-smoke-data.py --dry-run` 先看再删。

## 5. 监控与日志

| 看什么 | 在哪看 |
|---|---|
| AI 调用量/成功率/降级次数 | 管理端「系统监控」页（数据源 `GET /api/admin/monitor/ai-stats`） |
| 后端日志 | 控制台/日志文件；AI 降级为 WARN 级（功能名、耗时、降级结果） |
| ai-service 日志 | `ai_service.<功能> 调用开始/完成/失败 cost_ms=...`；**不记** key、请求体、wrongContext |
| 健康检查 | `GET http://127.0.0.1:8000/health` → `llmConfigured` 布尔（不回显 key） |

## 6. 故障处理 FAQ

| 现象 | 可能原因与处理 |
|---|---|
| 全部 AI 接口 502，业务正常 | ① ai-service 没起（`netstat -ano \| grep :8000`）；② DeepSeek key 失效（health 看 llmConfigured）；③ 模型名退役（改 DEEPSEEK_MODEL） |
| AI 例句一直显示内置例句 | 处于降级状态是**设计行为**，恢复 ai-service 即可；查 backend WARN 日志定位降级原因 |
| 用户反馈「登录已过期」频发 | JWT 有效期 7 天；若刚改过 jwt.secret 属正常（全员需重登） |
| 后端启动失败提示 JWT | jwt.secret 未配置或不足 32 字节 |
| 打卡数据不对 | 先看 `study_record.check_in_date`；热力图只按打卡日聚合，「学了没打卡」不算 |
| 迁移脚本执行报错 | 确认按编号顺序执行、未重复执行；V001 与 V002 幂等性不同，重复执行 V002 前先确认表不存在 |
| 管理端页面空白 | 确认后端 8080 在跑、浏览器控制台无 401；账号必须 ADMIN 角色 |

## 7. 升级与扩展注意事项

1. **契约先行**：改接口字段必须先改 `docs/接口详细设计.md`（含变更记录）再改代码——这是本项目铁律
2. **新增数据库变更**：只新增 `V003__xxx.sql`，绝不修改已执行的 V001/V002
3. **AI 提示词调整**：只改 `ai-service/app/chains/prompts.py`；调完跑 `pytest`（60 用例）与 `verify_live.py` 验证
4. **报告图结构**：`chains/report_graph.py` 的条件路由（数据不足快速路径）勿删——它省 LLM 调用且是答辩设计点
5. **勿引入依赖**：ai-service 不得引入任何数据库驱动（有自动化红线测试 `test_no_database_imports.py` 拦截）
6. **小程序 tabBar 上限 5 项**：微信硬限制；统计页是故意做成普通页的，勿改回 6 tab

## 8. 环境已知坑（维护时避开）

1. Windows GBK 终端：脚本必须 `PYTHONIOENCODING=utf-8`；输出统计用 Python 解析而非 `grep -c`
2. `mvn clean` 前先杀占用 jar 的旧 Java 进程
3. JDBC 连接串编码参数写 `UTF-8` 而非 `utf8mb4`
4. multipart 上传不要手设 `Content-Type`（会丢 boundary）
5. SQL 中不写 `CURDATE()` 等 MySQL 专有函数——时间边界一律由 Java 传参（保证 H2 测试同语义）

## 9. 联系与文档索引

- 接口契约：`docs/接口详细设计.md`（V1.2）
- 架构与数据表：`docs/S3-软件概要设计说明书-正文.md`（V1.4）
- 交接全景与踩坑记录：`handoff.md`（**改动前先读 §5 红线与 §11 检查清单**）
