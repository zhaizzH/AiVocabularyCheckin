# 前端开发规范

> 本项目前端开发的最佳实践。

---

## 概览

本目录包含前端开发规范，覆盖两个前端：`miniprogram/`（微信小程序原生
框架，学生端）与 `frontend/`（React 18 + TypeScript + Vite + Ant Design，
网页管理端）。

---

## 规范索引

| 规范文件 | 内容 | 状态 |
|----------|------|------|
| [目录结构](./directory-structure.md) | 双前端目录布局、模块组织、命名规范 | 已完成 |
| [组件规范](./component-guidelines.md) | 组件结构、props 约定、样式方式 | 已完成 |
| [Hooks 与页面逻辑](./hook-guidelines.md) | 自定义 hooks、小程序页面逻辑复用、数据请求 | 已完成 |
| [状态管理](./state-management.md) | 状态分类、全局状态准则、服务端状态 | 已完成 |
| [质量规范](./quality-guidelines.md) | 禁用/必须模式、测试要求、评审清单 | 已完成 |
| [类型安全](./type-safety.md) | TS 类型组织、与后端 DTO 对齐、禁用模式 | 已完成 |

---

## 编写代码前

1. 先读本目录与改动相关的规范文件
2. 网络请求只写在 `services/` 层，统一走 `utils/request`
3. 接口契约变更遵循"契约先行"，同步通知对接方

---

**语言**：所有规范文档使用**中文**编写。
