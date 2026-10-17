# frontend 管理端 · 实施记录

> 设计见 `design.md`；接口事实见 `research/01-admin-api-facts.md`（**实测优先于文档**）。

---

## 1. 阶段状态

| 阶段 | 内容 | 状态 | 提交 |
|---|---|---|---|
| S1 | 脚手架 + 类型 + 请求层 + services | ⬜ | — |
| S2 | 登录页 + 路由守卫 + 布局 | ⬜ | — |
| S3 | 词库管理 + JSONL 导入 | ⬜ | — |
| S4 | 单词管理弹窗 | ⬜ | — |
| S5 | 用户管理 | ⬜ | — |
| S6 | 系统监控 | ⬜ | — |
| S7 | 验收（脚本 + build + 实操） | ⬜ | — |

---

## 2. 环境与启动

```bash
# 后端（管理接口依赖）
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=local

# 前端
cd frontend && npm install && npm run dev
#   → http://localhost:5173（后端 CORS 白名单已含此端口）

# 建管理员账号（数据库原本 0 个 ADMIN）
python scripts/create-admin.py        # admin / Admin@12345
```

---

## 3. 必须在动手前知道的事实

全部来自 `research/01-admin-api-facts.md`，均为真实请求的返回：

1. **`code` 是字符串 `"0"`**，不是数字。
2. **`GET /api/admin/books` 的 `data` 是裸数组**；`words` / `users` 是分页包装
   `{list, total, page, size}`。两种形状分开建模。
3. **`1007` 是 HTTP 403 + code 1007**；`1006`/`1003` 是 HTTP 401。
   **axios 的非 2xx 走 reject 分支**，所以错误分支也必须做 code 分流，
   否则 401/403 永远不会触发跳登录。
4. **`1007` 不清 token**（登录态是好的，只是没权限）。
5. **ADMIN 行的禁用按钮 `disabled`**：后端返 `1005`，前端主动预防。
6. **用户列表的 `status` 查询参数被后端忽略**（实测传 `?status=0` 无效）→ 不做状态筛选。
7. **单词写操作后 `wordCount` 会变**（7508→7509）→ 必须回调刷新词书列表。
8. **`createTime` 是字符串**，不要 `new Date()` 解析。
9. **multipart 绝不能手工设 `Content-Type`**，否则丢 boundary。
10. **`successRate` 是数字百分数**（92.9），不乘 100。
11. **`2004` 只在整个文件无任何合法行时出现**；坏行本身只计入 `skipped`。

---

## 4. 分阶段任务清单

### S1：脚手架 + 类型 + 请求层 + services

- [ ] `npm create vite@latest frontend -- --template react-ts`（或手写 package.json）
- [ ] `npm i antd react-router-dom axios`
- [ ] `tsconfig.json` 开 `strict: true`、`noUnusedLocals`、`noUnusedParameters`
- [ ] `types/common.ts`（`Result<T>` 的 `data: T | null`、`PageResult<T>`）
- [ ] `types/{auth,book,word,user,monitor}.ts`，字段逐一对照 research §2–§9
- [ ] `utils/request.ts`：axios 实例 + 两个拦截器 + `ApiError` + 5 个导出函数
- [ ] `utils/auth.ts`：`getToken/setAuth/clearAuth/getUserInfo/isAdmin` + `auth-changed` 事件
- [ ] `utils/format.ts`：分类文案/颜色、`createTime` 原样展示、`null` → `—`
- [ ] `services/{auth,book,word,user,monitor}.ts`（一资源一文件）
- [ ] `scripts/verify_admin_api.py`：≥30 断言，直接打真实后端
- 完成判据：`npm run build` 零错误 + `verify_admin_api.py` 全绿

### S2：登录 + 守卫 + 布局

- [ ] `pages/Login/index.tsx`：Form + 判 role（非 ADMIN 不落 token）
- [ ] `components/RequireAdmin.tsx`：无 token / 非 ADMIN → `Navigate /login replace`
- [ ] `components/AdminLayout.tsx`：Sider 三项 + Header 显示当前账号 + 退出
- [ ] `App.tsx`：`createBrowserRouter` 路由表
- [ ] 登录态持久化：token 存 localStorage，刷新不丢
- [ ] 401 自动跳登录（停后端再恢复 token 场景手测）

### S3：词库管理

- [ ] `hooks/useBooks.ts`：`books / loading / error / reload`
- [ ] `pages/WordBank/index.tsx`：Table（名称/分类 Tag/单词数/创建时间/操作）
- [ ] 删除 `Popconfirm` + 成功后 reload
- [ ] `components/JsonlImportModal.tsx`：选文件 → 预览前 20 行（前端映射与后端一致）→ name+category → 提交 → 「成功 N 条，跳过 M 条」
- [ ] 导入成功后回调刷新词书列表

### S4：单词管理弹窗

- [ ] `hooks/useWords.ts`：`bookId / keyword / page / size` 受控，搜索重置 `page=1`
- [ ] `pages/WordBank/WordModal.tsx`：内嵌视图切换 `mode: 'list' | 'edit'`（不做双层 Modal）
- [ ] 表格分页受控 + 关键词搜索
- [ ] 新增/编辑 Form（word 必填）
- [ ] 删除 `Popconfirm`
- [ ] 写操作成功后：刷新单词列表 + `onChanged()` 刷新词书列表

### S5：用户管理

- [ ] `hooks/useUsers.ts`：`keyword / page / size` 受控
- [ ] `pages/UserMgr/index.tsx`：Table（ID/用户名/昵称/注册时间/累计打卡/状态/操作）
- [ ] 状态 Tag（1 绿 / 0 红）；禁用/启用 `Popconfirm`
- [ ] ADMIN 行按钮 `disabled` + Tooltip
- [ ] **跨端验证**：禁用某用户 → 该用户在小程序端请求返回 `1003`

### S6：系统监控

- [ ] 4 个 `StatCard`（调用总数/成功率/降级次数/注册用户数）
- [ ] 功能明细 Table（3 行，功能名中文化）
- [ ] 降级事件 Table（`reason` Tag 染色，空态文案）
- [ ] 顶部 `Alert`：「数据为服务启动以来累计值，重启清零」
- [ ] **触发真实降级**：停 ai-service → 调一次 AI → 监控页降级次数 +1

### S7：验收

- [ ] `npm run build` 零 TS 错误
- [ ] `scripts/verify_admin_api.py` 全绿
- [ ] `scripts/verify_admin_web.py` 静态检查全绿（无 any / services 外无裸请求 / 无 @ts-ignore）
- [ ] 浏览器实操 4 页 + 登录两种身份
- [ ] 清理探测用户（标记 `deleted=1`）

---

## 5. 反复会踩的陷阱（先写下来，实现时逐条对照）

### 5.1 axios 拦截器：非 2xx 不进成功分支

**症状**：401/403 时页面只弹「网络异常」，不跳登录页。
**原因**：HTTP 401/403 触发 `Promise.reject`，走第二个回调；只在第一个回调里判 `code` 等于没写。
**对策**：两个回调都要做 code 分流（design.md §3.3 已写）。

### 5.2 axios 拦截器返回 `body.data` 的类型谎言

**症状**：`http.get<T>()` 的返回类型仍是 `AxiosResponse<T>`，取属性全红。
**原因**：拦截器运行时改了返回值，但类型系统不知道。
**对策**：`unwrap<T>(p: Promise<unknown>): Promise<T>` 统一收口；**这是全项目唯一允许 `as` 的地方**。

### 5.3 FormData 手工设 Content-Type

**症状**：导入接口返回 9001 或 1005「文件为空」。
**原因**：手设 `multipart/form-data` 丢了 `boundary`，后端无法切分。
**对策**：`postForm` 不设该头，让 axios/浏览器自己决定。

### 5.4 搜索后停在高页码

**症状**：在第 5 页搜索，结果为空，用户以为没数据。
**对策**：任何 `keyword` 变化都 `setPage(1)`。

### 5.5 单词增删后词书 `wordCount` 是旧值

**症状**：加了 10 个词，词库页还是 7508。
**对策**：`WordModal` 的 `onChanged` 回调 → 父页 `reload()`。

### 5.6 非 ADMIN 登录后存了 token

**症状**：登录成功、跳转首页、每个请求 403、被弹回登录页，无限循环感。
**对策**：登录响应里先判 `role`，非 ADMIN 直接报错且**不落 token**。

### 5.7 把 `1007` 当 `1006`

**症状**：管理员权限被撤时，被当成「登录过期」，清掉 token，无法排查。
**对策**：`1007` 单独分支，不清 token。

### 5.8 `createTime` 用 `new Date()` 解析

**症状**：显示 `Invalid Date`（Safari 对 `yyyy-MM-dd HH:mm:ss` 不容错）。
**对策**：原样展示字符串。

### 5.9 AntD `Table` 的 `rowKey`

**症状**：列表渲染警告 key 重复/缺失，操作列的按钮点到别的行。
**对策**：`rowKey="bookId"` / `"wordId"` / `"userId"`，绝不用 index。

### 5.10 `useEffect` 里请求 + 依赖数组写错

**症状**：翻页触发两次请求，或搜索框输一个字发一次请求。
**对策**：请求依赖 `[bookId, keyword, page, size]`；搜索用 `onSearch`（回车/点按钮）而非 `onChange`。

---

## 6. 验证脚本

### 6.1 `scripts/verify_admin_api.py`（接口契约，≥30 断言）

直接打 `127.0.0.1:8080`，覆盖：

- 登录：ADMIN 成功 / 密码错 1002 / 普通用户 403+1007 / 无 token 401+1006
- 词书：3 本、字段集合精确、`wordCount` = 7508/5651/9602
- 单词：分页字段 `list/total/page/size`、关键词命中、增删改、`wordCount` 联动
- 用户：分页、搜索、禁用→该用户 1003、启用恢复、禁 ADMIN → 1005
- 导入：好行/坏行计数、非法 category → 1005、全坏行 → 2004
- 删除词书：2001（不存在）、成功后词书数 -1
- 监控：字段集合、`successRate` 是数字、`features` 3 项、`recentEvents` 结构

### 6.2 `scripts/verify_admin_web.py`（静态检查）

- `services/` 之外无 `fetch(` / `axios` / `http.`（豁免 `utils/request.ts`）
- 无 `any`（`:\s*any\b`、`<any>`、`as any`）
- 无 `@ts-ignore` / `@ts-nocheck`
- 无 `as ` 强转（豁免 `utils/request.ts` 的 `unwrap`）
- `types/` 中 API 类型名与后端 DTO 对应
- `package.json` 依赖白名单
- `rowKey` 在每处 `<Table` 都出现（防 index key）
- 剥离注释后再匹配（复用小程序的教训）

### 6.3 `npm run build`

`tsc -b && vite build` 零错误。

---

## 7. 完成定义（DoD）

- [ ] `npm install && npm run dev` 可启动，`npm run build` 通过
- [ ] 四页可访问，侧边栏导航正确，刷新不丢登录态
- [ ] 普通用户登录被拒且不落 token
- [ ] 词书列表显示真实 7508 / 5651 / 9602
- [ ] 真实 JSONL 导入成功并显示成功/跳过计数；含坏行时跳过数正确
- [ ] 单词分页/搜索/增删改生效，`wordCount` 同步变化
- [ ] 用户搜索/禁用/启用生效；ADMIN 行按钮禁用
- [ ] 禁用后该用户在小程序端立即无法访问（跨端）
- [ ] 监控数值来自真实接口；触发降级后计数增加
- [ ] `types/` 与后端 DTO 字段名一致（抽查 5 个接口）
- [ ] 401 自动跳登录
- [ ] 无 `any` 滥用；`services/` 之外无裸请求
- [ ] 静态检查与接口契约脚本全绿
