# vocab-backend — AI 单词学习打卡 · 业务后端

Spring Boot 3.5 + MyBatis-Plus + MySQL 8 的业务后端。全系统**唯一**连接
MySQL 的服务；AI 生成能力由独立的 `ai-service`（FastAPI）提供，本服务只做
调用方与降级兜底。

> 当前进度：**阶段 2（工程骨架）已完成**。数据库迁移与业务接口在阶段 3 起实现。

---

## 1. 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | 实测 21.0.11 通过 |
| Maven | 3.9+ | 实测 3.9.16 通过 |
| MySQL | 8.0+ | 实测 8.4.9；需 `utf8mb4` 字符集 |

## 2. 首次运行步骤

### 2.1 建库

```sql
CREATE DATABASE IF NOT EXISTS ai_vocab
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
```

> 表结构由`src/main/resources/db/migration/` 下的迁移脚本创建（阶段 3 交付）。

### 2.2 配置本地凭据

真实凭据**不入库**。从模板复制一份：

```bash
cp src/main/resources/application-local.yml.example \
   src/main/resources/application-local.yml
```

然后填入 MySQL 密码与本机 JWT 密钥（`application-local.yml` 已被
`.gitignore` 排除，不会提交）。

### 2.3 构建与启动

```bash
mvn clean package                 # 构建 + 跑单测
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

启动后服务监听 `http://127.0.0.1:8080`。

### 2.4 快速自测

```bash
# 无 token 访问受保护接口 -> HTTP 401 + {"code":"1006",...}
curl -i http://127.0.0.1:8080/api/client/auth/profile

# CORS 预检放行 -> HTTP 200
curl -i -X OPTIONS http://127.0.0.1:8080/api/client/auth/profile \
  -H 'Origin: http://localhost:5173' \
  -H 'Access-Control-Request-Method: GET'
```

## 3. 环境变量清单

所有敏感项**无默认值**，必须显式提供，否则启动失败（fail-fast）——
这是刻意设计：宁可起不来，也不要一个带开发默认密钥的实例被误部署。

| 变量 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `JWT_SECRET` | ✅ | 无 | HS256 签名密钥，≥ 32 字节。`openssl rand -base64 48` |
| `DB_PASSWORD` | ✅ | 无 | MySQL 密码 |
| `DB_HOST` | — | `127.0.0.1` | |
| `DB_PORT` | — | `3306` | |
| `DB_NAME` | — | `ai_vocab` | |
| `DB_USERNAME` | — | `root` | |
| `AI_SERVICE_BASE_URL` | — | `http://127.0.0.1:8000` | AI 微服务地址 |
| `WX_APPID` / `WX_SECRET` | — | 空 | 留空则 `wx-login` 返回 1004，账号密码登录不受影响 |
| `CORS_ALLOWED_ORIGINS` | — | `localhost:5173,127.0.0.1:5173` | 管理端来源，逗号分隔 |

> 本地开发可直接写进 `application-local.yml`，无需导出环境变量。

## 4. 项目结构

```
src/main/java/com/vocab/
├── VocabApplication.java          启动类
├── common/                        与框架无关的公共契约
│   ├── Result.java                统一响应 {code,message,data}，成功码 "0"
│   ├── ErrorCode.java             错误码枚举（1xxx 认证 / 2xxx 词库 /
│   │                              3xxx 学习 / 4xxx 错题 / 5xxx AI / 9xxx 系统）
│   ├── BizException.java          业务异常（所有可预期失败都抛它）
│   ├── PageResult.java            统一分页 {list,total,page,size}
│   ├── Constants.java             业务常量（每日 20 词、复习间隔 1/3/7/15 天）
│   └── GlobalExceptionHandler.java 全局异常 → Result 转换
└── config/
    ├── MybatisPlusConfig.java     分页插件 + create_time/update_time 自动填充
    ├── JwtUtil.java               JWT 签发与解析（jjwt 0.12 API）
    ├── JwtInterceptor.java        鉴权拦截器（401/403、禁止即时生效）
    ├── UserContext.java           当前登录用户 ThreadLocal
    ├── UserStatusValidator.java   查库校验账号状态的扩展点（阶段 5 接入）
    ├── WebMvcConfig.java          拦截器注册 + CORS
    └── RestTemplateConfig.java    调用 ai-service 的 HTTP 客户端（显式超时）
```

## 5. 契约约定（关键，改动前务必确认）

这些约定是前端（小程序 + 管理端）的硬依赖，改动即破坏：

1. **统一响应结构** `{code, message, data}`；`code == "0"` 表示成功，
   前端一律用 `code !== "0"` 判断失败。
2. **业务失败 HTTP 状态码仍为 200**，错误通过 `code` 表达。
   仅以下情况保留真实 HTTP 状态：
   - `401` 鉴权失败（token 缺失/无效/过期/账号禁用）
   - `403` 非管理员访问 `/api/admin/**`
   - `404` / `405` / `415` 路由层错误（接口不存在 / 方法不支持 / 媒体类型不支持）
3. **所有列表接口必须分页**，参数 `page`（默认 1）、`size`（默认 20）。
4. **绝不对外暴露实体**，一律在 Service 边界转换为 DTO
   （防泄露 `password_hash`、`deleted`）。
5. **Controller 只做参数校验 + 调用一个 Service 方法 + 包装返回**，
   不含业务逻辑，不直接调 Mapper。

## 6. 测试

```bash
mvn test                              # 全部单测（用 H2 内存库，不依赖本机 MySQL）
mvn test -Dtest=JwtUtilTest           # 单个测试类
```

骨架阶段已有 37 个单测，锁定上述契约（响应结构、错误码分段、JWT 六类失败
场景、401/403/404 行为、异常处理器不泄露内部信息）。

## 7. 与 ai-service 的边界

- 本服务是**唯一**调用 `ai-service` 的一方；前端绝不直连 AI 服务。
- 三个 AI 接口全部有**显式超时**（例句 15s / 讲解 30s / 报告 60s），
  且失败时走降级（细节见 `docs/接口详细设计.md` §9）。
- `ai-service` **不连 MySQL**——它无状态，数据一律由本服务传入。
