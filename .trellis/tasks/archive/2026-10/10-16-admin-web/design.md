# frontend 管理端 · 技术设计

> 依据：`prd.md`、`docs/接口详细设计.md` V1.2 §8、`docs/prototype/admin-prototype.html`、
> `.trellis/spec/frontend/*`，以及 **`research/01-admin-api-facts.md`（真实接口实测，优先于文档）**。

---

## 1. 架构总览

单页应用（SPA），React 18 + TypeScript + Vite + Ant Design 5。

```
浏览器 (localhost:5173)
   │
   │ fetch  ──►  src/services/*.ts  ──►  src/utils/request.ts
   │                                        │
   │                                        │ 唯一出网口：baseURL + Bearer + code 校验 + 401/403 分流
   │                                        ▼
   └────────────────────────────►  backend :8080  /api/**
                                        │
                                        └──► ai-service :8000（仅后端调用，前端不可见）
```

**三层职责（不可越界）**

| 层 | 职责 | 禁止 |
|---|---|---|
| `pages/` `components/` | 渲染、交互、局部状态 | 禁止 `fetch` / `axios` / 拼 URL / 判 `code` |
| `services/` | 一个后端资源一个文件，函数即接口 | 禁止 `setState`、禁止 UI 逻辑 |
| `utils/request.ts` | 唯一网络出口 | 禁止业务语义 |

与 `miniprogram/` 的 `pages → services → utils/request.js` 同构，两条前端走同一套分层心智。

---

## 2. 目录结构

```
frontend/
├── index.html
├── package.json
├── tsconfig.json
├── tsconfig.node.json
├── vite.config.ts
└── src/
    ├── main.tsx
    ├── App.tsx                  # 路由表 + 布局 + 守卫
    ├── vite-env.d.ts
    ├── types/
    │   ├── common.ts            # Result<T>、PageResult<T>
    │   ├── auth.ts              # LoginRequest / LoginResponse / UserInfo / UserRole
    │   ├── book.ts              # AdminBook / ImportResult
    │   ├── word.ts              # Word / WordUpsertRequest / WordDeleteResult
    │   ├── user.ts              # AdminUser / UserStatusRequest
    │   └── monitor.ts           # AiStats / AiFeatureStat / DegradeEvent
    ├── utils/
    │   ├── request.ts           # 唯一出网口
    │   ├── auth.ts               # token/userInfo 存取、登录态判断
    │   └── format.ts            # 时间/空值展示、分类文案映射
    ├── services/
    │   ├── auth.ts
    │   ├── book.ts
    │   ├── word.ts
    │   ├── user.ts
    │   └── monitor.ts
    ├── hooks/
    │   ├── useAuth.ts           # 登录态 + 当前用户
    │   ├── useBooks.ts          # 词书列表（含手动 reload）
    │   ├── useWords.ts          # 单词分页 + 搜索（受控）
    │   └── useUsers.ts          # 用户分页 + 搜索（受控）
    ├── components/
    │   ├── AdminLayout.tsx      # Sider + Header + Outlet
    │   ├── RequireAdmin.tsx     # 路由守卫
    │   ├── StatCard.tsx         # 监控统计卡
    │   └── JsonlImportModal.tsx # 预览 + 确认导入
    └── pages/
        ├── Login/index.tsx
        ├── WordBank/index.tsx
        ├── WordBank/WordModal.tsx
        ├── UserMgr/index.tsx
        └── Monitor/index.tsx
```

> `pages/` 按功能建目录（`WordBank/`），组件与页面同目录——与 spec「按功能组织」一致。

---

## 3. `utils/request.ts`（唯一网络出口）

封装 **axios**（用户选定）。选它的理由：拦截器让「注入 token / 解包 Result / 错误分流」
能各写一处，而不是每个方法里重复；且 axios 自带 `timeout`，不必手写 AbortController。

### 3.1 骨架

```ts
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8080';

const http = axios.create({ baseURL: BASE_URL, timeout: 15000 });
```

### 3.2 请求拦截器

```ts
http.interceptors.request.use((cfg) => {
  const token = getToken();
  if (token) cfg.headers.Authorization = `Bearer ${token}`;
  return cfg;
});
```

`FormData` 场景**不要**在拦截器里设 `Content-Type`——让浏览器自动带 `boundary`，
手设会导致后端 multipart 解析失败。axios 1.x 对 `FormData` 会自动跳过 JSON 头。

### 3.3 响应拦截器（**核心设计**）

```ts
http.interceptors.response.use(
  (resp) => {
    const body = resp.data as Result<unknown>;
    const code = body.code;

    if (code === '1006' || code === '1003') {
      clearAuth();
      kickToLogin('登录已过期，请重新登录');
      return Promise.reject(new ApiError(code, '登录已过期，请重新登录', resp.status));
    }
    if (code === '1007') {
      // 登录态本身没问题，只是角色不够 —— 不清 token
      kickToLogin('该账号无管理员权限');
      return Promise.reject(new ApiError(code, '该账号无管理员权限', resp.status));
    }
    if (code !== '0') {
      return Promise.reject(new ApiError(code, body.message, resp.status));
    }
    return body.data;
  },
  (err) => {
    // 网络错误 / 超时 / 非 2xx
    if (axios.isCancel(err) || err.code === 'ECONNABORTED') {
      return Promise.reject(new ApiError('CLIENT_TIMEOUT', '请求超时，请稍后重试', 0));
    }
    const status = err.response?.status;
    const body = err.response?.data as Result<unknown> | undefined;
    if (body?.code) {
      // 401/403 也带 Result 包装：复用上面的 code 分流逻辑
      if (body.code === '1006' || body.code === '1003') { clearAuth(); kickToLogin('登录已过期，请重新登录'); }
      if (body.code === '1007') { kickToLogin('该账号无管理员权限'); }
      return Promise.reject(new ApiError(body.code, body.message, status ?? 0));
    }
    if (status === 401) {
      clearAuth(); kickToLogin('登录已过期，请重新登录');
      return Promise.reject(new ApiError('1006', '登录已过期，请重新登录', 401));
    }
    return Promise.reject(new ApiError('CLIENT_NETWORK', '网络异常，请检查后端服务是否启动', status ?? 0));
  },
);
```

**为什么错误分支也要处理**：实测 `401` 带 body（`1006`）、`403` 带 body（`1007`），
HTTP 非 2xx 会走 axios 的 reject 分支而**不进**成功分支。若只在成功分支分流，
401/403 就完全不会触发跳登录——这是 axios 最容易踩的坑。

**为什么 `1007` 不清 token**：登录态本身是好的，只是角色不够。清掉会让
「管理员权限被撤」与「登录过期」两种情况无法区分，排查时看不到任何线索。

**跳转为何用 `window.location.replace`**：`request.ts` 不在 React 树内，拿不到
router 的 `navigate`；`replace` 还能防止用户按返回键回到已失效页面。

### 3.4 类型与解包

axios 的 `interceptors.response` 返回 `body.data` 会让 TS 认为响应是 `AxiosResponse`，
类型对不上。用**包装函数**收口，而不是在拦截器里硬改类型：

```ts
async function unwrap<T>(p: Promise<unknown>): Promise<T> {
  return (await p) as T;
}

export const get  = <T>(path: string) => unwrap<T>(http.get(path));
export const post = <T>(path: string, body?: unknown) => unwrap<T>(http.post(path, body));
export const put  = <T>(path: string, body?: unknown) => unwrap<T>(http.put(path, body));
export const del  = <T>(path: string) => unwrap<T>(http.delete(path));
export const postForm = <T>(path: string, form: FormData) => unwrap<T>(http.post(path, form));
```

> 这里是**全项目唯一允许 `as` 的地方**——axios 拦截器返回值无法在类型层面表达，
> 属于第三方库类型限制。静态检查脚本对该文件豁免，其余文件出现 `as` 即失败。

### 3.5 错误类型

```ts
export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly httpStatus: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}
```

页面统一 `catch (e) { message.error(e instanceof ApiError ? e.message : '未知错误') }`。

## 4. 类型与后端 DTO 对齐

`types/common.ts`：

```ts
export interface Result<T> {
  code: string;
  message: string;
  data: T | null;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  size: number;
}
```

> `data: T | null` 而非 `data: T`——实测失败响应 `data` 是 `null`，
> 写成非空会让调用方以为可以无脑取属性。

| 类型 | 字段（实测核对） |
|---|---|
| `UserRole` | `'USER' | 'ADMIN'`（联合字面量，非 string） |
| `UserInfo` | `userId: number; username: string; nickname: string; avatar: string | null; role: UserRole` |
| `LoginResponse` | `{ token: string; userInfo: UserInfo }` |
| `AdminBook` | `bookId, name, category, wordCount, description: string | null, createTime` |
| `Word` | `wordId, word, phonetic: string | null, translation: string | null, example: string | null` |
| `AdminUser` | `userId, username, nickname, role: UserRole, status: 0 | 1, totalCheckInDays, createTime` |
| `AiStats` | `totalCalls, successRate, degradedCount, registeredUsers, features: AiFeatureStat[], recentEvents: DegradeEvent[]` |
| `DegradeEvent.reason` | `'TIMEOUT' | 'ERROR' | 'CIRCUIT_OPEN'` |

**`category` 用联合字面量**：`type BookCategory = 'CET4' | 'CET6' | 'KAOYAN'`——
导入表单的 Select 直接从类型派生选项，后端改枚举时 TS 会报错提醒。

---

## 5. 路由与守卫

```
/login              Login         公开
/                   AdminLayout   受保护
  ├── /wordbank     WordBank      词库管理（默认页）
  ├── /users        UserMgr       用户管理
  └── /monitor      Monitor       系统监控
*                   → 重定向 /
```

- 用 `react-router-dom` v6 `createBrowserRouter` + `<Outlet />`。
- `RequireAdmin`：无 token → `Navigate to="/login" replace`；有 token 但 `role !== 'ADMIN'` → 同样跳登录并提示。
- **不刷新丢登录态**（PRD 验收项）：token 存 `localStorage`，`RequireAdmin` 每次渲染从 storage 读，不做「只在登录时 setState」。
- 登录后 `replace('/')`；退出登录 `replace('/login')`。

> 为何不用 `token` 放内存/Context：刷新即丢，PRD 明确要求「不刷新丢登录态」。

---

## 6. 各页面设计

### 6.1 `pages/Login/`

- AntD `Form` + `Input` + `Input.Password` + `Button`。
- 提交 → `services/auth.ts#login` → 成功判 `userInfo.role`：
  - `ADMIN` → 存 token + userInfo → `replace('/')`
  - 非 ADMIN → **不存 token**，`message.error('该账号无管理员权限，请使用管理员账号登录')`
- 失败 → `message.error(e.message)`（覆盖 `1002 密码错`、`1003 禁用`）。
- 密码框 `autoComplete="current-password"`，回车即提交（`onFinish` 天然支持）。

**为什么非 ADMIN 不存 token**：存了等于给了半个登录态，后续每个请求都会 403，
用户看到的是「登录成功但什么都打不开」——比直接拒绝更糟。

### 6.2 `pages/WordBank/` — 词库管理

`Table` 列：词书名 / 分类（Tag 染色）/ 单词数 / 创建时间 / 操作。

- 操作列：`查看单词`（开 `WordModal`）、`删除`（`Popconfirm` 二次确认）。
- 删除成功 → `message.success` + `reload()`。
- 顶部右侧：`+ 导入词库（JSONL）` → 开 `JsonlImportModal`。
- 空态：`Table` 自带 `locale.emptyText`。

**分类 Tag 颜色映射**（`utils/format.ts`）：
`CET4 → blue`、`CET6 → green`、`KAOYAN → purple`，未知分类 → `default`。
未知分类不能崩——后端枚举将来可能增加。

### 6.3 `pages/WordBank/WordModal.tsx` — 单词管理弹窗

`Modal`（宽 900）+ 顶部工具栏 + `Table` + 分页。

- 工具栏：`Input.Search`（关键词）+ `Button type="primary"`「新增单词」。
- 表格列：单词 / 音标 / 释义 / 例句 / 操作（编辑、删除）。
- 分页：`Table` 的 `pagination` 受控（`current` / `pageSize` / `total`），
  `showSizeChanger`，`pageSizeOptions=[10,20,50]`。
- 新增/编辑：弹窗内嵌 `Form`（word 必填、phonetic/translation/example 选填）。
- 删除：`Popconfirm`。
- **任何写操作成功后**：刷新单词列表 **+ 回调父页面 `onChanged()` 刷新词书列表**
  （`wordCount` 会变，实测 7508 → 7509）。

> 「表单」用嵌套 `Modal` 还是同弹窗内切换，选择**同弹窗内切换视图**（`mode: 'list' | 'edit'`）：
> 避免 AntD 双层 Modal 的层级与焦点问题，也少一次动画。

关键词搜索：受控 `keyword` state，`onSearch` 时重置 `page=1`（否则搜完停在第 5 页看到空表）。

### 6.4 `pages/UserMgr/`

`Table` 列：ID / 用户名 / 昵称 / 注册时间 / 累计打卡 / 状态 / 操作。

- 状态用 `Tag`：`1 → 绿「正常」`、`0 → 红「已禁用」`。
- 操作列：`禁用` / `启用` 按钮（`Popconfirm` 确认，因为影响真实用户）。
- **`role === 'ADMIN'` 的行按钮 `disabled`**，`Tooltip` 说明「管理员账号不可禁用」
  ——实测后端返 `1005`，前端主动预防。
- 顶部 `Input.Search` 按用户名搜索，搜索时 `page=1`。

**不做状态筛选**：实测后端忽略 `status` 参数（见 research §5），做了就是假功能。

### 6.5 `pages/Monitor/`

- 顶部 4 个 `StatCard`：AI 调用总数 / 成功率 / 降级次数 / 注册用户数。
- 中部 `Table`：按功能拆分（example / explain / report），列
  `功能 / 调用 / 成功 / 降级 / 超时 / 错误 / 平均耗时`。
  功能名映射中文：`example → 例句生成`、`explain → 错题讲解`、`report → 学习报告`。
- 底部 `Table`：降级事件（时间 / 功能 / 耗时 / 原因 / 结果）。
  `reason` Tag 染色：`TIMEOUT → orange`、`ERROR → red`、`CIRCUIT_OPEN → volcano`（未知 → default）。
- 空态：`recentEvents` 为空时显示「暂无降级事件」。
- 页面顶部 `Alert type="info"`：「数据为服务启动以来的累计值，重启后清零」——
  PRD 非目标里写明是一期内存计数器，不说明会被当成 bug。

`successRate` 是数字百分数（92.9），**直接显示 `92.9%`，不乘 100**。

---

## 7. JSONL 导入交互

`components/JsonlImportModal.tsx`，三步：

```
选择文件 → 前端读取预览前 20 行 → 确认导入 → 展示「成功 N 条，跳过 M 条」
```

1. **选择**：`Upload.Dragger` + `beforeUpload` 返回 `false`（阻止自动上传），
   `accept=".jsonl"`。拿到 `File` 后用 `file.text()` 读取。
2. **预览**：按行 `split('\n')`，逐行 `JSON.parse`，**坏行不中断**——
   映射 `word / uk→phonetic / translations→translation / sentences[0]→example`，
   与后端 `WordImportServiceImpl` 的映射规则一致（否则预览与落库不一致，是欺骗）。
   预览表只渲染前 20 行有效行，同时显示「共 N 行，可解析 M 行」。
3. **确认**：`Form` 填 `name`（必填）+ `category`（Select，仅 CET4/CET6/KAOYAN）→
   `FormData` 提交 `file` + `name` + `category` → `services/book.ts#importBook`。
4. **结果**：`Alert type="success"`：「导入完成：成功 N 条，跳过 M 条」+ 关闭后刷新词书列表。

**关键**：`postForm` 绝不能手工设 `Content-Type: multipart/form-data`——
浏览器必须自己带 `boundary`，手工设会导致后端解析失败（这是最常见的 multipart 坑）。

---

## 8. 状态管理

| 数据 | 存放 | 理由 |
|---|---|---|
| token / userInfo | `localStorage` + `useAuth` 读取 | 刷新不丢（PRD 验收） |
| 词书列表 | `useBooks` 的 `useState` | 服务端状态，按页面请求 |
| 单词分页 | `useWords` 的 `useState` | 同上，搜索/翻页即重取 |
| 用户分页 | `useUsers` 的 `useState` | 同上 |
| 弹窗开关、表单值 | 组件内 `useState` | 纯 UI 状态 |

- **不引 Redux/Zustand**：跨组件共享的只有登录态一项，`useAuth` 足够。
- **服务端数据不进全局状态**（spec 硬约束），也就不会有一致性维护问题。

`useAuth` 用 `useSyncExternalStore` 还是 `useState`？→ **`useState` + 自定义事件**：
token 变更点只有登录/登出/401 三处，`utils/auth.ts` 里 `setAuth`/`clearAuth` 后
`window.dispatchEvent(new Event('auth-changed'))`，`useAuth` 监听即可。不引额外库。

---

## 9. 分阶段实施计划

| 阶段 | 内容 | 完成判据 |
|---|---|---|
| **S1** | 工程脚手架 + `utils/request` + `types/*` + `services/*` | `npm run build` 通过；`scripts/verify_admin_api.py` 用真实后端跑通 5 个 service 的接口 |
| **S2** | 登录页 + `RequireAdmin` 守卫 + `AdminLayout` | 管理员能进；普通用户被拒；刷新不丢登录态；401 自动跳登录 |
| **S3** | 词库管理页 + `JsonlImportModal` | 3 本词书显示 7508/5651/9602；真实 JSONL 导入成功/跳过计数正确 |
| **S4** | 单词管理弹窗 | 分页/搜索/增删改生效；`wordCount` 同步刷新 |
| **S5** | 用户管理页 | 搜索/禁用/启用生效；ADMIN 行按钮 disabled；跨端验证禁用后小程序无法访问 |
| **S6** | 监控页 | 4 个卡片数值来自真实接口；降级事件表；触发降级后计数增加 |
| **S7** | 验收：`npm run build` + 浏览器实操 + 文档 | TS 零错误；无 `any`；services 外无裸 fetch |

---

## 10. 验收方式

### 10.1 脚本化（可重复）

`scripts/verify_admin_api.py`：用真实后端逐接口核对
「字段名、类型、错误码、`wordCount` 联动、ADMIN 禁禁用、导入成功/跳过计数」，
共 ≥30 断言。这层不依赖浏览器，能进 CI。

### 10.2 静态检查（复用小程序思路）

`scripts/verify_admin_web.py`：
- `services/` 之外不出现 `fetch(` / `axios`（正则扫 `src/`，排除 `utils/request.ts`）
- 无 `any`、无 `@ts-ignore`、无 `as ` 强转（扫描 `src/**/*.ts(x)`）
- `types/` 里的 API 类型名与后端 DTO 一致（抽查映射表）
- `package.json` 无多余依赖（只允许 react / react-dom / antd / react-router-dom / axios + 构建相关 dev 依赖）

> 网络层用 **axios**：拦截器让 token 注入 / Result 解包 / 错误分流各写一处，
> 自带 `timeout`。代价是多一个约 14KB(gzip) 依赖，由 `utils/request.ts` 完全收口。

### 10.3 浏览器实操（必须人工）

- 登录（管理员 / 普通用户两种）
- 刷新页面不丢登录态
- 词库列表真实数字、导入真实 JSONL、单词增删改后数字变化
- 用户禁用后，用该账号在小程序端请求 → 1003（跨端）
- 触发一次 AI 降级（停 ai-service）→ 监控页降级次数 +1

---

## 11. 风险与对策

| 风险 | 对策 |
|---|---|
| `books` 裸数组 vs `words` 分页包装混淆 | 两套类型分开建模，`services` 里标注返回形状 |
| 把 `1007` 当 `1006` 处理导致无解释踢下线 | `request.ts` 分流 + 专门断言 |
| multipart 手工设 Content-Type 导致解析失败 | `postForm` 不设 header，注释写明 |
| 单词写操作后词书 `wordCount` 不刷新 | `onChanged` 回调链，S4 断言 |
| 搜索结果停在高页码看到空表 | 搜索一律 `page=1` |
| 非 ADMIN 登录后存了 token 造成「登录成功但全 403」 | 登录页判 role，非 ADMIN 不落 token |
| `createTime` 字符串被 `new Date()` 解析成 Invalid Date | 统一走 `format.ts` 原样展示，不转 Date |
| 管理员账号禁自己被后端拒 | 前端 `disabled` 预防 |

---

## 12. 需要用户确认的项

1. ADMIN 测试账号：`admin` / `Admin@12345`（脚本 `scripts/create-admin.py` 创建，可改）。
2. 前端端口沿用 Vite 默认 `5173`（后端 CORS 白名单已含此端口）。
3. 是否需要在验收后删除探测产生的测试用户（`admprobe_*`、`edge_*`）。
