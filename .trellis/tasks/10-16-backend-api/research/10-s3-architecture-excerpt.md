# S3 摘录：体系结构与分层设计（3.1–3.7）

> 摘自 `docs/S3-软件概要设计说明书-正文.md`，裁剪章节以便子代理上下文注入。
> **权威来源仍是原文档**，冲突时以原文档为准。

---

**3.1 体系结构设计总述**

系统总体采用前后端分离的分层体系结构，整体分四层：

1. **表示层**：微信小程序端（学生端）与网页管理端（React），负责页面展示与用户交互，统一通过 `services/` 网络层调用业务后端接口。
2. **业务逻辑层**：Spring Boot 业务后端，实现用户、词库、学习、打卡、错题、复习、统计与管理等全部业务逻辑，是唯一连接数据库的服务。
3. **AI 服务层**：FastAPI + LangChain AI 微服务，无状态部署，不连接数据库，所需数据由业务后端查询后传入，负责调用大模型 API 生成例句、讲解与报告。
4. **数据层**：MySQL 8.0 数据库，保存用户、词书、单词、学习记录与错题数据。

系统分层结构示意如下：

```
微信小程序端（学生）          网页管理端（管理员）
      │  REST（HTTPS）             │  REST（HTTPS）
      └────────────┬──────────────┘
                   ▼
        Spring Boot 业务后端（Controller-Service-DAO）
                   │                                │
        ┌──────────┴──────────┐                     │  HTTP
        ▼                     ▼                     ▼
   MySQL 8.0（唯一数据源）    统一缓存/降级策略   FastAPI + LangChain AI 微服务
                                                     │
                                                     ▼
                                                大模型 API（OpenAI 兼容）
```

**关键约束**：两个前端绝不直接请求 `ai-service/`，AI 内容一律经业务后端接口中转并支持降级；`ai-service/` 无任何数据库连接，永远不添加。

**3.2 总体架构概述**

（一）**业务后端（backend/）**：采用 Controller-Service-DAO 三层架构。

- Controller 层：接收请求、参数校验、调用 Service，不写业务规则；统一返回 `Result<T>` 包装结构。
- Service 层：承载业务规则（打卡连续天数计算、复习列表生成、AI 调用编排与降级），抛出自定义 `BizException`。
- DAO 层：基于 MyBatis-Plus 的 Mapper 接口与实体类，默认 `LambdaQueryWrapper` 动态查询，列表一律 `Page` 分页。

（二）**AI 微服务（ai-service/）**：FastAPI 应用，按功能提供 3 个接口（例句生成、错题讲解、学习报告），内部使用 LangChain 组织提示词模板与多步 Chain（学习报告为"拉取数据→分析→生成"多步编排）；无数据库连接、无状态，可独立水平扩展。

（三）**双前端**：小程序端（`miniprogram/`）按功能组织页面（index/study/review/mistakes/stats）；管理端（`frontend/`）按后端资源组织页面与类型（词库管理、用户管理、监控）。两端网络请求只发生在 `services/` 层，统一走 `utils/request`（baseURL、token 注入、`code !== "0"` 错误处理、401 跳转）。

**3.3 表示层设计**

（一）**微信小程序端（miniprogram/）**：

| **目录/页面** | **职责** |
|---------------|----------|
| pages/index   | 首页：今日学习计划、快捷入口 |
| pages/study   | 每日学习：单词卡片学习、打卡 |
| pages/review  | 间隔复习：待复习列表、复习作答 |
| pages/mistakes| 错题本：错题列表、AI 讲解入口 |
| pages/stats   | 数据统计：打卡热力图、掌握度曲线 |
| components/   | 可复用组件：单词卡片、打卡日历格、统计图表容器 |
| services/     | API 请求函数，按后端资源分文件 |
| utils/        | request 封装、日期与间隔复习计算等工具 |

页面状态用 `data` + `setData`；全局少量状态（当前用户、token、已选词库）用 `globalData` + `wx.Storage` 持久化；时效数据（打卡状态、错题数）在 `onShow` 刷新，一次性数据（词库详情）在 `onLoad` 加载。

（二）**网页管理端（frontend/）**：

| **目录/页面** | **职责** |
|---------------|----------|
| src/pages/WordBank | 词库管理：词库列表、单词增删改查、词库导入 |
| src/pages/UserMgr  | 用户管理：用户列表、禁用/启用 |
| src/pages/Monitor  | 系统监控：AI 调用统计、降级事件查看 |
| src/components/    | 可复用组件（AntD 基础上封装） |
| src/services/      | API 请求函数，按后端资源分文件 |
| src/hooks/         | 自定义 hooks（useWordBanks、useCheckInStatus 等） |
| src/types/         | 与后端 DTO 对齐的 TypeScript 类型 |

组件内状态用 `useState`；少量跨组件状态用 `useContext`；服务端数据不放入全局状态，进入页面按需请求。

**3.4 业务逻辑层设计**

业务后端按功能模块划分包结构（以 `com.vocab` 为根包）：

```
com.vocab
├── controller/        # REST 控制器（auth、book、study、mistake、review、ai、admin、stats）
├── service/           # 业务服务接口
├── service/impl/      # 业务服务实现
├── mapper/            # MyBatis-Plus Mapper 接口
├── entity/            # 数据库实体（User、WordBook、Word、StudyRecord、MistakeEntry 等）
├── dto/               # 接口传输对象（XxxRequest / XxxResponse）
├── common/            # 统一响应 Result、错误码 ErrorCode、全局异常处理器
└── config/            # 安全配置（JWT）、跨域、MyBatis-Plus 自动填充等
```

各服务模块职责划分：

| **服务** | **主要职责** |
|----------|--------------|
| AuthService        | 注册、登录（密码 BCrypt 哈希）、JWT 签发与校验 |
| WordBookService    | 词库列表、词库选择、词库导入（批量）、单词 CRUD |
| StudyService       | 每日学习计划生成、学习打卡、连续打卡天数计算 |
| MistakeService     | 错题自动收录、错题列表、标记已掌握 |
| ReviewService      | 按间隔复习算法生成待复习列表 |
| AiService          | AI 接口编排：调用 ai-service、超时控制、降级回退 |
| StatsService       | 打卡热力图、掌握度曲线聚合统计 |
| AdminService       | 用户列表、禁用/启用、系统监控数据 |

**3.5 AI 微服务层设计（ai-service/）**

AI 微服务采用 FastAPI，目录结构如下：

```
ai-service/
├── app/
│   ├── main.py           # FastAPI 应用入口、路由注册
│   ├── core/             # 配置、日志统一配置、大模型客户端（OpenAI 兼容）
│   ├── chains/           # LangChain 编排：例句 Chain、讲解 Chain、报告 Chain
│   ├── schemas/          # 请求/响应模型（Pydantic）
│   └── routers/          # /ai/v1/example、/ai/v1/explain、/ai/v1/report
├── requirements.txt
└── .env.example          # API 密钥环境变量模板（不入库、不入仓）
```

设计要点：无状态服务，不连接任何数据库；大模型 API 密钥只从环境变量读取；失败抛 `HTTPException`，detail 使用稳定字符串；业务后端将一切 AI 侧失败映射为自有 `5xxx` 错误码，前端只面对一套错误词汇表。

**3.6 数据层设计**

\(1\) 数据源：MySQL 8.0，InnoDB 引擎，全库 `utf8mb4`；仅 `backend/` 可连接数据库。

\(2\) 访问层：MyBatis-Plus；动态查询默认 `LambdaQueryWrapper`；列表接口一律 `Page` 分页；批量操作用 `saveBatch` / `removeByIds`；统计查询（热力图、掌握度曲线）在 SQL 中完成聚合。

\(3\) 迁移管理：数据库结构以纯 SQL 脚本管理，存放于 `backend/src/main/resources/db/`，每次变更新增有序脚本（`V001__init.sql`、`V002__xxx.sql`……），可直接导出作为课设交付物。

\(4\) 公共字段：每张表包含 `id`（BIGINT 自增主键）、`create_time`、`update_time`（DATETIME，MyBatis-Plus 自动填充）；面向用户数据（用户、学习记录、错题）使用逻辑删除（`deleted` 字段 + `@TableLogic`），物理删除仅限中间表记录。

**3.7 域模型设计**

系统核心域模型如下：

| **实体**       | **说明**                                   | **关键字段** |
|----------------|--------------------------------------------|--------------|
| User           | 用户（普通用户/管理员）                    | username、password_hash、role、status |
| WordBook       | 词书（四级/六级/考研）                     | name、category、word_count |
| Word           | 单词（属于某词书）                         | word、phonetic、translation、example |
| UserWordBook   | 用户 ↔ 词书 多对多中间表（已选词书）        | user_id、book_id |
| StudyRecord    | 学习记录（含每日打卡）                     | user_id、word_id、study_date、check_in_date、streak_days |
| MistakeEntry   | 错题记录                                   | user_id、word_id、wrong_count、mastered |

关系说明：一个用户可选多本词书，一本词书可被多个用户选择（`UserWordBook` 中间表）；一本词书含多个单词（`Word.book_id`）；用户对单词产生多条学习记录（`StudyRecord`）；错题（`MistakeEntry`）由学习/复习中答错的单词生成。

---

