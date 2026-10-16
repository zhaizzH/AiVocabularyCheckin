# 质量规范

> 后端开发的代码质量标准。

---

## 概览

适用于 `backend/`（Spring Boot）与 `ai-service/`（FastAPI）两个服务。
目标：队友一次评审即可读懂、符合同级规范文件约定的代码。本项目是
交付物固定的课设——优先简单直白的实现，不要花哨的抽象。

---

## 禁用模式

- ❌ Controller 里写业务逻辑——Controller 只做参数校验、调用一个
  Service 方法、包装返回结果。
- ❌ Controller 返回实体（会泄露密码哈希 / `deleted` 标志）。
- ❌ 用 `System.out.println` / `print()` 输出除一次性本地调试外的任何内容。
- ❌ 代码中硬编码 URL、密钥、凭证——一律走 `application.yml` + 环境变量
  （backend）、`pydantic-settings` 环境配置（ai-service）。大模型密钥
  只存在于环境变量中（需求规格说明书安全要求）。
- ❌ `ai-service/` 引入数据库驱动或建立 MySQL 连接。
- ❌ 吞掉异常（`catch (Exception e) {}`）或"记录日志后又原样抛出"。
- ❌ 新接口绕过 `Result` 包装或自创错误结构。
- ❌ Python：裸 `except:`、可变默认参数、`requirements.txt` 不锁版本
  （LangChain 迭代快——必须锁版本，见风险分析）。

---

## 必须遵循的模式

- ✅ Controller → Service → Mapper 分层；边界处使用 DTO。
- ✅ 每个请求 DTO 加 `@Valid` 与校验注解。
- ✅ 所有接口返回 `Result<T>`；所有错误经由 `BizException` + 全局处理器
  （见 `error-handling.md`）。
- ✅ 每一次 AI 调用都有超时控制与降级兜底。
- ✅ 所有列表接口使用 MyBatis-Plus `Page` 分页。
- ✅ 密码仅存 BCrypt 哈希；除公开接口外一律做 token 鉴权（JWT 或拦截器
  校验的会话 token），管理端接口还要校验管理员权限。
- ✅ FastAPI：每个路由函数都有类型化的 Pydantic 请求/响应模型
  （不接受裸 `dict` 请求体）。

---

## 测试要求

- 针对 Service 层业务规则写单元测试（JUnit 5 / pytest）：连续打卡天数
  计算、间隔复习调度、错题本增删。
- 每个 AI 接口至少一个集成测试证明**降级路径**可用
  （AI 服务宕机 → 接口仍返回合法包装结构）。
- 每次提交涉及接口改动的代码前，做一次手工 API 冒烟测试
  （Apifox/Postman 集合或 curl——保持可重复执行）。

---

## 代码评审清单

- [ ] 分层正确（Controller 不调 Mapper、不写业务逻辑）
- [ ] 新增/变更接口返回 `Result` 且复用现有错误码
- [ ] 代码与日志中无密钥、token 等敏感信息
- [ ] 列表接口已分页；批量写入使用批量 API
- [ ] 涉及 AI 的改动有经过验证的降级路径
- [ ] 数据库变更附带编号 SQL 迁移脚本
- [ ] 前端可见的变更符合接口契约（契约先行——按风险分析约定，
      先定契约再并行开发）
