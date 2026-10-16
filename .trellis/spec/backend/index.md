# 后端开发规范

> 本项目后端开发的最佳实践。

---

## 概览

本目录包含后端开发规范，覆盖两个后端服务：`backend/`（Spring Boot 3 +
MyBatis-Plus 业务后端）与 `ai-service/`（FastAPI + LangChain AI 微服务）。

---

## 规范索引

| 规范文件 | 内容 | 状态 |
|----------|------|------|
| [目录结构](./directory-structure.md) | 双后端目录布局、模块组织、命名规范 | 已完成 |
| [数据库规范](./database-guidelines.md) | MyBatis-Plus 查询模式、SQL 迁移脚本、命名规范 | 已完成 |
| [错误处理](./error-handling.md) | Result 包装、错误码分段、AI 降级路径 | 已完成 |
| [质量规范](./quality-guidelines.md) | 禁用/必须模式、测试要求、评审清单 | 已完成 |
| [日志规范](./logging-guidelines.md) | 日志级别、格式、敏感信息红线 | 已完成 |

---

## 编写代码前

1. 先读本目录与改动相关的规范文件
2. 修改常量/配置前先全局搜索引用（见 `.trellis/spec/guides/`）
3. 跨层改动（接口契约、DTO、错误码）需同步前端

---

**语言**：所有规范文档使用**中文**编写。
