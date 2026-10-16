# 类型安全

> 本项目的类型约定（主要适用于 React 管理端）。

---

## 概览

- **管理端**：TypeScript 严格模式（`strict: true`），类型集中定义在
  `frontend/src/types/`，与后端 DTO 一一对应。
- **小程序端**：原生 JavaScript（无 TS）。类型安全的等价手段是：
  `services/` 中每个请求函数用 JSDoc 标注参数与返回结构，并在
  `utils/request` 层对响应包装做统一校验（`code` / `data` 结构）。

---

## 类型组织

- `types/` 按后端资源分文件：`types/user.ts`、`types/wordBank.ts`、
  `types/mistake.ts`……文件名与 `services/` 对应。
- 与后端交互的类型命名对齐后端 DTO：`UserResponse`、`WordBankResponse`、
  `CheckInRequest`。前端自定义的纯 UI 类型加 `Ui` 前缀或留在组件文件内，
  不与 API 类型混淆。
- 后端 `Result<T>` 包装在 `types/common.ts` 中定义一次：
  `interface Result<T> { code: string; message: string; data: T }`，
  services 层所有函数返回 `Promise<Result<XxxResponse>>`。

---

## 校验

- 管理端：表单校验用 AntD `Form` 的 rules；接口入参类型由 TypeScript
  编译期保证，不引入 Zod 等运行时校验库（项目规模不需要）。
- 小程序端：对后端返回的关键字段（id、状态枚举）在使用处做存在性判断，
  不假设字段永远存在——AI 降级时响应结构可能与正常路径不同。

---

## 常见模式

- 用联合字面量类型表达后端枚举：`type CheckInStatus = 'done' | 'missed' | 'pending'`，
  不用裸 `string`。
- 泛型约束 services 层：`function get<T>(url: string): Promise<Result<T>>`，
  调用处显式指定 `T`。
- 类型守卫收窄 AI 响应：`if ('fallback' in res.data) { /* 降级 UI */ }`。

---

## 禁用模式

- ❌ `any`——确实无法避免的第三方类型用 `unknown` + 类型守卫。
- ❌ 强制类型断言（`as Xxx`）绕过编译器——先检查类型定义是否该修正。
- ❌ 在组件里重复定义 `types/` 中已有的 API 类型——一律 import。
- ❌ `@ts-ignore` / `@ts-nocheck` 提交进仓库。
- ❌ 小程序里把后端包装响应当成裸数据用（`res.data.list` 直接点）——
  必须先过 `utils/request` 的 `code` 校验。
