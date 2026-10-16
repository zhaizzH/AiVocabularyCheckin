# 目录结构

> 本项目后端代码的组织方式。

---

## 概览

本项目包含**两个后端服务**，各自位于独立的顶层目录：

| 目录 | 技术栈 | 职责 |
|------|--------|------|
| `backend/` | Java 17 · Spring Boot 3.x · MyBatis-Plus | 业务后端：用户、词库、学习计划、打卡、错题本、统计 |
| `ai-service/` | Python 3.10+ · FastAPI · LangChain | 无状态 AI 微服务：AI 例句、错题讲解、学习报告 |

核心架构规则（来自需求规格说明书）：**AI 微服务绝不直接访问 MySQL**。
`ai-service/` 所需数据全部由 `backend/` 查询后通过请求体传入，AI 服务只返回生成内容。
这样实现业务与 AI 解耦——AI 服务宕机时，核心学习功能仍可正常使用。

---

## 目录布局

```
backend/                          # Spring Boot 业务后端
├── src/main/java/<group>/        # 例如 com.example.wordcheck
│   ├── controller/               # REST 接口，每个资源一个类
│   ├── service/                  # 业务逻辑
│   │   └── impl/                 # Service 实现类（Spring Boot 惯例）
│   ├── mapper/                   # MyBatis-Plus Mapper 接口
│   ├── entity/                   # MyBatis-Plus 实体（与表一一对应）
│   ├── dto/                      # 请求/响应对象（绝不对外暴露实体）
│   ├── config/                   # CORS、拦截器、MyBatis-Plus 配置
│   ├── common/                   # Result 包装类、错误码、常量
│   └── exception/                # 自定义异常 + @RestControllerAdvice 全局处理器
├── src/main/resources/
│   ├── application.yml           # 基础配置；密钥一律来自环境变量
│   └── mapper/                   # MyBatis XML（仅当 Wrapper 无法表达时使用）
└── src/test/java/                # 与 main 包结构保持一致

ai-service/                       # FastAPI AI 微服务
├── app/
│   ├── main.py                   # FastAPI 应用入口、路由注册
│   ├── routers/                  # 每个 AI 功能一个路由（例句、讲解、报告）
│   ├── chains/                   # 各功能的 LangChain 链 / 提示词模板
│   ├── schemas/                  # Pydantic 请求/响应模型
│   └── core/                     # 配置（环境变量）、LLM 客户端工厂、日志
└── tests/
```

---

## 模块组织

- **Spring Boot 三层架构，调用方向严格单向**——`controller → service → mapper`。
  Controller 不得直接调用 Mapper；Mapper 不得包含业务逻辑。
- 每个资源一个 `@RestController`（`UserController`、`WordBankController`、
  `CheckInController`……），每个 AI 功能一个 FastAPI 路由。
- **`backend/` 是唯一允许调用 `ai-service/` 的服务**。前端绝不直接请求
  AI 服务——一律通过业务后端的接口中转，并由业务后端做降级处理。
- 统一响应包装类和错误码放在 `backend/.../common/`；所有 Controller 返回
  相同的包装结构（见 `error-handling.md`）。

## 命名规范

- Java：包名全小写；类名 `PascalCase` 并带角色后缀
  （`UserService`、`UserMapper`、`UserController`）；DTO 命名 `XxxRequest` / `XxxResponse`。
- MyBatis-Plus 实体通过 `@TableName` 对应表名；类名不加前缀。
- Python：模块名 `snake_case`；Pydantic 模型 `XxxRequest` / `XxxResponse`；
  路由文件按功能命名（`sentences.py`、`explain.py`、`report.py`）。
- REST 路径使用复数名词、不用连字符：`/api/users`、`/api/word-banks`、
  `/api/check-ins`。AI 接口在业务后端统一挂在 `/api/ai/...` 下。

---

## 示例

- AI 调用链路：`frontend/ 或 miniprogram/ → backend/.../controller/AiController
  → service/AiService → HTTP 调用 ai-service/ → LangChain 链 → 大模型 API`。
  当 `ai-service/` 超时或返回 5xx 时，业务后端返回降级响应而非报错
  （需求规格说明书非功能需求第 3 条）。
