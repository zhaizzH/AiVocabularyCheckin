# S4 安装部署说明

> 项目：AI 单词学习打卡系统（AiVocabCheckin）· 第 N 组
> 本文档配套 S4 程序包（源码 + 数据库脚本），覆盖：环境要求、首次部署、启动顺序、验证方法、生产部署差异与常见问题。
> 版本：V1.0 · 2026-10-17

---

## 1. 环境要求

| 依赖 | 版本 | 用途 | 验证命令 |
|---|---|---|---|
| JDK | 21（编译级别 Java 17） | 业务后端 | `java -version` |
| Maven | 3.9+ | 后端构建（支持离线 `mvn -o`） | `mvn -v` |
| MySQL | 8.0+（实测 8.4.9） | 数据库，`utf8mb4` + InnoDB | `mysql --version` |
| Python | 3.12+ | AI 微服务 + 运维脚本 | `python --version` |
| Node.js | 18+（实测 24） | 管理端构建 | `node -v` |
| 微信开发者工具 | 最新稳定版 | 小程序运行/预览 | — |

## 2. 程序包结构

```
AiVocabularyCheckin/
├── backend/       # Spring Boot 业务后端（唯一连数据库的服务）
│   └── src/main/resources/db/V001__init.sql、V002__word_progress.sql   # 数据库脚本
├── ai-service/    # FastAPI + LangChain AI 微服务（无状态，不连数据库）
├── miniprogram/   # 微信原生小程序（学生端）
├── frontend/      # React 18 + AntD 5 管理端
├── data/vocab-source/   # 词库源数据（四级/六级/考研 JSONL，共 22761 词）
├── scripts/       # 建号/拉取词库等运维脚本
└── docs/          # 契约与设计文档
```

## 3. 首次部署（从干净克隆开始）

### 3.1 数据库初始化

```bash
# 建库（注意：勿动同实例上的其它库）
mysql -uroot -p -e "CREATE DATABASE ai_vocab DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# 执行迁移脚本（顺序执行）
mysql -uroot -p ai_vocab < backend/src/main/resources/db/V001__init.sql
mysql -uroot -p ai_vocab < backend/src/main/resources/db/V002__word_progress.sql
```

### 3.2 私有配置（不入仓，必须手工创建）

| 文件 | 模板 | 必填项 |
|---|---|---|
| `backend/src/main/resources/application-local.yml` | 同目录 `.example` | MySQL 密码、`jwt.secret`（≥32 字节随机串）、`wx.appid` / `wx.secret` |
| `ai-service/.env` | `.env.example` | `DEEPSEEK_API_KEY`（其余有安全默认值；`DEEPSEEK_MODEL` 默认 `deepseek-flash`，旧模型名 `deepseek-chat` 已退役） |

### 3.3 拉取依赖与构建

```bash
cd backend    && mvn -q clean package -DskipTests        # 出 target/vocab-backend-1.0.0.jar
cd ai-service && python -m venv .venv && .venv/Scripts/python -m pip install -r requirements.txt
cd frontend   && npm install
```

### 3.4 导入种子词库（一次性，幂等）

```bash
# 方式一：启动参数触发（推荐）
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--vocab.seed=true
# 方式二：管理端「词库管理 → 导入词库」上传 data/vocab-source/*.jsonl
```

验收：`word_book` 3 行（7508 / 5651 / 9602），`word` 表共 22761 行。

### 3.5 创建管理员账号（一次性，幂等）

```bash
python scripts/create-admin.py          # 默认 admin / Admin@12345
python scripts/create-admin.py --promote <username>   # 把已有账号提为 ADMIN
```

## 4. 启动顺序（本地开发）

服务间依赖：小程序/管理端 → backend → ai-service。**先起依赖，后起调用方。**

| 顺序 | 服务 | 命令 | 端口 |
|---|---|---|---|
| 1 | MySQL | 系统服务常驻 | 3306 |
| 2 | backend | `cd backend && java -jar target/vocab-backend-1.0.0.jar --spring.profiles.active=local` | 8080 |
| 3 | ai-service | `cd ai-service && .venv/Scripts/python -m uvicorn app.main:app --port 8000` | 8000 |
| 4 | 管理端 | `cd frontend && npm run dev` | 5173 |
| 5 | 小程序 | 微信开发者工具导入 `miniprogram/` 目录，填入 AppID 后编译 | — |

**降级演示**：步骤 3 不启动（或中途 `taskkill` 掉 8000 端口进程），学习/打卡/复习/错题/统计全部照常，AI 例句返回内置例句并标记 `degraded:true`。

## 5. 部署后验证

| 验证 | 命令 | 预期 |
|---|---|---|
| 后端单元测试 | `cd backend && mvn -o test` | 326 tests / 0 failures |
| 后端一键冒烟（含真实 MySQL 与降级断言） | `DBPASS=<密码> bash backend/scripts/smoke-test.sh` | 143 用例 0 FAIL |
| AI 服务真实调用 | `cd ai-service && .venv/Scripts/python scripts/verify_live.py` | 三接口返回契约字段，耗时 < 12/25/50s |
| 小程序验证 | `cd miniprogram && python scripts/verify_all.py` | 8 套件 314 断言全绿 |
| 管理端验证 | `python scripts/verify_admin_web.py` 等 4 套 | 189 断言全绿 |

> 注意：跑 `smoke-test.sh` 前确保 8000 端口无监听（脚本刻意验证 ai-service 不可达时的降级路径）。

## 6. 环境变量总清单

| 变量 | 位置 | 说明 |
|---|---|---|
| `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD` | application-local.yml | MySQL 连接 |
| `jwt.secret` | application-local.yml | ≥32 字节，无默认值（fail-fast） |
| `wx.appid` / `wx.secret` | application-local.yml | 微信登录（code2Session） |
| `ai.base-url` / `ai.timeout-ms` | application.yml | ai-service 地址与超时（默认 8000 / 按接口 15/30/60s） |
| `DEEPSEEK_API_KEY` | ai-service/.env | 必填，缺失时调用返回 `ai_service_llm_not_configured`（503） |
| `DEEPSEEK_BASE_URL` / `DEEPSEEK_MODEL` | ai-service/.env | 默认 `https://api.deepseek.com` / `deepseek-flash` |
| `EXAMPLE/EXPLAIN/REPORT_TIMEOUT_S` | ai-service/.env | 自控超时 12/25/50s（必须小于 backend） |

## 7. 生产部署与本地开发的差异

| 项 | 本地开发 | 生产部署 |
|---|---|---|
| backend | `java -jar` 直接跑 | 同命令，建议 systemd 守护 + 日志切割 |
| ai-service | uvicorn 单进程 | uvicorn `--workers 2` 起多进程（无状态可直接横向扩展） |
| 管理端 | Vite dev（5173） | `npm run build` 产物交由 Nginx 静态托管 |
| 小程序 | 开发者工具预览 | 微信公众平台提审发布，需在后台配置 request 合法域名（HTTPS） |
| 数据库 | 本机 MySQL | 独立实例，开启每日备份（`mysqldump ai_vocab`） |
| 密钥 | `.env` / `application-local.yml` | 环境变量注入，绝不进仓库与镜像 |

## 8. 常见问题（FAQ）

| 问题 | 排查 |
|---|---|
| AI 接口突然全部 502 | 先查 `ai-service/.env` 的 `DEEPSEEK_MODEL`——供应商会退役模型名（`deepseek-chat` 已退役，当前 `deepseek-flash`） |
| 启动后端报 JWT 相关错误 | `jwt.secret` 未配置或 <32 字节 |
| 小程序登录报 `invalid code` | AppID/secret 与微信公众平台不一致；或 code 已过期（code 一次性且 5 分钟有效） |
| 管理端 `npm` 脚本报 esbuild 失败 | `frontend/node_modules` 未安装，先 `npm install` |
| Windows 下脚本中文乱码/报错 | 命令前加 `PYTHONIOENCODING=utf-8`；脚本内避免 U+2212 等特殊字符 |
| 端口被占用 | `netstat -ano \| grep :8080` 找 PID 后 `taskkill /F /PID <pid>` |

## 9. 备份与恢复

```bash
# 备份
mysqldump -uroot -p ai_vocab > ai_vocab_$(date +%F).sql
# 恢复
mysql -uroot -p ai_vocab < ai_vocab_2026-10-17.sql
```

词库源数据在 `data/vocab-source/`，误删可重新导入（导入幂等）。用户数据只存在于 `ai_vocab` 库，备份该库即可完整恢复。
