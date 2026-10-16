# 组件规范

> 本项目组件的编写方式。

---

## 概览

- **小程序端**：微信原生自定义组件（`Component({ ... })`），用于跨页面复用的
  UI（单词卡片、打卡日历格、统计图表容器）。页面级一次性 UI 直接写在页面
  WXML 里，不强行抽组件。
- **管理端**：React 18 函数组件 + TypeScript，UI 一律基于 Ant Design 5
  （`Table`、`Form`、`Modal`、`Button` 等），不重复造 AntD 已有的组件。

---

## 组件结构

小程序组件文件顺序保持一致：

```js
Component({
  properties: { /* 1. 属性 */ },
  data: { /* 2. 内部状态 */ },
  observers: { /* 3. 监听器 */ },
  lifetimes: { /* 4. 生命周期 */ },
  methods: { /* 5. 方法：先事件处理，后私有辅助 */ },
})
```

React 组件文件顺序保持一致：

```tsx
// 1. 类型定义（Props 接口）
// 2. 组件函数（hooks 集中在顶部：useState → useEffect → 自定义 hooks）
// 3. 事件处理函数（handleXxx）
// 4. 渲染（JSX）
// 5. 文件内的纯辅助函数放在组件外、文件底部
```

---

## Props 约定

- React：每个组件用显式的 `interface XxxProps` 定义 props，必填/可选标注
  清楚；事件回调统一命名 `onXxx`（`onSelect`、`onDelete`）。
- 小程序：`properties` 中每个属性声明 `type` 与默认值；组件向父级通信
  用 `triggerEvent('xxx')`，事件名用 `camelCase`。
- 两个端都遵循：**组件不直接发网络请求**（列表/表单页组件除外）——数据由
  页面传入，组件通过回调通知页面，保持组件可复用、可预览。

---

## 样式方式

- 小程序：WXSS，使用 `rpx` 适配常见机型（SRS 界面要求）；类名用
  `kebab-case`；页面样式写在页面自己的 `.wxss`，避免污染全局 `app.wxss`。
- 管理端：AntD 主题与组件样式为主；少量自定义样式用 CSS Modules
  （`Xxx.module.css`）；不写内联 `style` 对象，除非是动态计算值。
- 两端的圆角、主色、字号以 AntD 默认值/小程序设计指南为准，不自定义
  整套设计系统。

---

## 可访问性

- 本项目可访问性要求以"移动端操作习惯"为主（SRS）：可点击元素尺寸足够、
  AI 请求等耗时操作必须有加载态与超时提示（SRS 响应时间要求）。
- 管理端表单用 AntD `Form.Item` 的 `label` 关联控件；图片提供中文 `alt`；
  按钮文案为动词短语（"导入词库"而非图标裸奔）。

---

## 常见错误

- 在小程序组件里直接 `wx.request`——请求必须走 `services/`，否则 token
  注入和错误码处理会漏掉。
- React 组件里展开巨型 JSX（几百行不分块）——按职责拆子组件，但也不要
  为两三行 JSX 建文件。
- 用数组下标当 React `key`；列表项有稳定 id 时必须用 id。
- 小程序里用 `this.setData` 一次性传整个大对象——只更新变化的字段，
  避免渲染卡顿。
