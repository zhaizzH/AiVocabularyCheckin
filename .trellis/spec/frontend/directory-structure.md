# 目录结构

> 本项目前端代码的组织方式。

---

## 概览

本项目包含**两个前端**，各自位于独立的顶层目录：

| 目录 | 技术栈 | 职责 |
|------|--------|------|
| `miniprogram/` | 微信小程序原生框架（WXML / WXSS / JS） | 学生端：学习、打卡、复习、AI 功能 |
| `frontend/` | React 18 · TypeScript · Vite · Ant Design | 网页管理端：词库/单词维护、用户管理、监控 |

两个前端都只调用 `backend/`（Spring Boot 业务后端），**绝不直接请求
`ai-service/`**。AI 内容经由业务后端接口中转并支持降级。

---

## 目录布局

```
miniprogram/                      # 微信小程序（学生端）
├── pages/                        # 页面，每个页面一个目录
│   ├── index/                    # index.js / index.json / index.wxml / index.wxss
│   ├── study/                    # 每日学习
│   ├── review/                   # 间隔复习
│   ├── mistakes/                 # 错题本
│   └── stats/                    # 数据统计
├── components/                   # 可复用自定义组件
├── utils/                        # request 封装、日期/间隔复习计算等工具
├── services/                     # API 请求函数，按后端资源分文件
├── app.js / app.json / app.wxss  # 入口与全局配置
└── project.config.json

frontend/                         # React 管理端
├── src/
│   ├── pages/                    # 路由页面（词库管理、用户管理、监控）
│   ├── components/               # 可复用组件
│   ├── services/                 # API 请求函数，按后端资源分文件
│   ├── hooks/                    # 自定义 hooks
│   ├── types/                    # 与后端 DTO 对应的 TypeScript 类型
│   ├── utils/                    # request 封装等工具
│   └── App.tsx / main.tsx
├── index.html
└── vite.config.ts
```

---

## 模块组织

- 按**功能**组织，不按文件类型组织。一个功能相关的页面、组件、请求函数
  聚在一起，而不是把所有组件堆进一个全局目录。
- `services/` 是唯一的网络层：页面和组件不写裸 `wx.request` / `fetch`，
  一律调用 services 中的函数；services 内部统一走 `utils/request` 封装
  （baseURL、token 注入、`code !== "0"` 错误处理、401 跳转）。
- 共享类型/常量集中在 `frontend/src/types/` 与小程序的 `utils/` 中，
  与后端 DTO 命名保持一致，避免各页面各自定义同名结构。

## 命名规范

- 小程序：页面与组件目录用 `kebab-case`；Page/Component 构造器内的
  方法与数据字段用 `camelCase`。
- React：组件文件与组件名用 `PascalCase`（`WordTable.tsx`）；hooks 用
  `useXxx`；工具与 services 用 `camelCase`；类型用 `PascalCase`。
- 目录一律小写。

---

## 示例

- 小程序错题本页面：`pages/mistakes/`（视图 + 交互）→
  `services/mistake.js`（API 调用）→ `utils/request.js`（统一封装）。
- 管理端词库页：`pages/WordBank/` → `services/wordBank.ts` →
  `utils/request.ts`；页面使用的类型定义在 `types/wordBank.ts`。
