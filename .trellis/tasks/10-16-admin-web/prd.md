# frontend 管理端（React 18 + TypeScript + Vite + Ant Design 5）

## Goal

实现网页管理端，让管理员完成词库维护、用户管理与系统监控三项运营工作。

## 依赖与顺序

- **依赖 `10-16-backend-api` 的 `/api/admin/**` 接口**（含决策补齐的 3 个接口）。
- 是本任务树中**优先级最低**（P2）的一项；backend 与小程序优先级更高。

## 需求（依据 S3 §3.3/§6.3 + `admin-prototype.html`）

### 工程与技术

- React 18 + TypeScript + Vite + Ant Design 5。
- 目录结构（S3 §3.3 指定）：

```
frontend/
├── src/pages/      WordBank, UserMgr, Monitor, Login
├── src/components/ 在 AntD 基础上封装的复用组件
├── src/services/   按后端资源分文件
├── src/hooks/      自定义 hooks（useWordBanks、useCheckInStatus 等）
├── src/types/      与后端 DTO 对齐的 TypeScript 类型
└── src/utils/      request（baseURL、token 注入、code!=="0" 处理、401 跳转）
```

- 网络请求只写在 `services/`，统一走 `utils/request`。
- 组件内状态用 `useState`；跨组件少量状态用 `useContext`；**服务端数据不进全局状态**，进页面按需请求。
- `types/` 必须与后端 DTO 对齐（契约先行）。
- **复用 AntD 组件（Table/Form/Modal/Button），不自建设计系统。**

### 页面

| 页面 | 要点 |
|---|---|
| 登录页 | 管理员账号密码登录（走 `/api/client/auth/login`，契约 §1.2 说明管理员同用此接口，由 role 区分）；非 ADMIN 登录后应被拒绝进入管理端 |
| 词库管理 | 词书表格（名称/分类/单词数/创建时间/操作）；「查看单词」打开单词管理弹窗；「删除」二次确认（逻辑删除）；「+ 导入词库（JSONL）」上传 + 预览前 20 行 + 确认导入 → 展示「成功 N 条，跳过 M 条」 |
| 单词管理弹窗 | 单词分页表格 + 关键词搜索；新增/编辑表单（word 必填 / phonetic / translation / example）；删除二次确认；`wordCount` 变化后表格同步刷新 |
| 用户管理 | 用户表格分页 + 用户名搜索；列含 ID/用户名/昵称/注册时间/累计打卡/状态/操作；禁用/启用按钮；**ADMIN 账号的禁用按钮应不可用或提示不允许** |
| 系统监控 | AI 统计卡片（今日调用次数 / 成功率 / 降级次数 / 注册用户数）；降级事件表格（时间/功能/耗时/降级结果） |

### 导入交互（原型明确要求）

- 点击或拖拽上传 `.jsonl`；
- 前端读取并**预览前 20 行**（映射后的 word/phonetic/translation/example 表格）；
- 确认后 `multipart/form-data` 提交 `file` + `name` + `category`；
- 结果提示「成功 N 条，跳过 M 条」。

## 验收标准

- [ ] `npm install && npm run dev` 可启动，`npm run build` 通过（TS 无类型错误）。
- [ ] 四个页面均可访问，路由与侧边栏导航正确（不刷新丢登录态）。
- [ ] 登录：普通用户账号登录后**不能**进入管理端（后端 403 应被前端正确处理与提示）。
- [ ] 词库管理：列表正确显示 3 本词书与真实单词数（7508/5651/9602）。
- [ ] 导入：上传真实 JSONL 成功并展示成功/跳过计数；上传含坏行文件时跳过计数正确。
- [ ] 单词管理：分页、搜索、新增、编辑、删除均生效，且词书 `wordCount` 同步变化。
- [ ] 用户管理：搜索、禁用/启用生效；**禁用后该用户在小程序端立即无法访问**（跨端验证）。
- [ ] 监控页：数值来自 `GET /api/admin/monitor/ai-stats`；触发一次 AI 降级后降级次数增加。
- [ ] `types/` 类型与后端 DTO 字段名一致（抽查 5 个接口）。
- [ ] 401 处理：token 失效自动跳登录页。
- [ ] 无 `any` 滥用；`services/` 之外不出现裸 axios/fetch 调用。

## 非目标

- 不做移动端适配（管理端面向桌面浏览器）。
- 不做权限细分（仅 ADMIN / 非 ADMIN 二分，契约无更细角色）。
- 不做完整监控平台（一期内存计数器数据即可）。
- 不做词库导入进度实时推送（同步等待 + loading 即可）。
