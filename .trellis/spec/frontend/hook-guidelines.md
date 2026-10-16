# Hooks 与页面逻辑规范

> 本项目自定义 hooks（管理端）与小程序页面逻辑复用的约定。

---

## 概览

- **管理端（React）**：跨组件复用的状态逻辑抽成自定义 hook，放在
  `frontend/src/hooks/`。只在单个组件内使用的逻辑留在组件里，不过早抽象。
- **小程序端**：没有 hooks 概念；等价物是 `utils/` 中的纯函数与
  `behaviors`。重复 3 次以上的页面逻辑（如分页加载）抽成 behavior 或
  utils 函数。

---

## 自定义 Hook 模式

- 每个 hook 一个文件，文件名即 hook 名（`useWordBanks.ts`）。
- hook 返回一个对象 `{ data, loading, error, refresh }` 而不是元组，
  便于扩展字段时不破坏调用方。
- hook 内部调用 `services/` 的函数——hook 不直接写 `fetch`。
- 副作用（请求、订阅）必须在 `useEffect` 内并正确清理；依赖数组如实填写，
  不为通过 lint 而乱加 `eslint-disable`。

---

## 数据请求

- **两端的网络请求都只发生在 `services/` 层**；hook / 页面调用 services
  函数，再由 `utils/request` 统一处理 baseURL、token、`code !== "0"`。
- 管理端直接用 `useState` + `useEffect` 管理请求状态即可——本项目规模
  不引入 React Query / SWR 等请求库（与状态管理规范一致：保持灵活、
  不强制单一库）。
- 小程序页面在 `onShow` 刷新需要最新状态的数据（打卡状态、错题数），
  在 `onLoad` 加载一次性数据（词库详情）。

---

## 命名规范

- 自定义 hook 以 `use` 开头：`useWordBanks`、`useCheckInStatus`。
- 小程序 behavior 命名 `xxxBehavior`；utils 函数用动词开头的
  `camelCase`（`formatStreak`、`buildReviewList`）。

---

## 常见错误

- 把请求写进 `useEffect` 却不处理组件卸载后的 setState——用取消标志或
  AbortController。
- 在 hook 里静默吞掉错误：错误必须出现在返回值 `error` 字段中，由页面
  决定如何展示（Toast / 降级 UI）。
- 小程序多个页面各自复制同一段分页逻辑——第二次出现时就应该抽 utils /
  behavior。
