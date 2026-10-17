# miniprogram 学生端 · 技术设计

> 依据：`prd.md` + `docs/学生端-需求分析与原型设计.md` + `docs/接口详细设计.md` + `research/01-backend-contract-facts.md`
> 技术栈：微信原生小程序（WXML / WXSS / JS），**零第三方库**（不引 ECharts/uCharts/npm 包）。
> 版本：V1.0 · 2026-10-17

---

## 1. 架构总览

```
页面 (pages/*)  ──调用──▶  services/*.js  ──调用──▶  utils/request.js  ──wx.request──▶  backend :8080
     │                        │                            │
     └──用──▶ components/*     └──用──▶ utils/constants.js  └──401/网络异常统一处理
```

**三层职责铁律**（`frontend/quality-guidelines.md` 硬约束）：

| 层 | 只做 | 绝不做 |
|---|---|---|
| `pages/` | 视图、交互、页面局部状态 | `wx.request`、拼 URL、判 `code` |
| `services/` | 按资源封装 API 函数（入参 → 出参） | 弹 toast、跳页面、碰 `setData` |
| `utils/request.js` | baseURL、token 注入、`code !== "0"`、401 处理、网络异常 | 业务判断 |

**前端绝不直连 `ai-service:8000`**——AI 三接口一律经 backend 中转（`/api/client/ai/*`）。

---

## 2. 目录结构

```
miniprogram/
├── app.js                     # globalData + 启动时读 Storage 恢复登录态
├── app.json                   # 六 tabBar + 11 个页面注册 + window 配置
├── app.wxss                   # 仅全局变量/重置/通用原子类（不含页面专属样式）
├── sitemap.json
├── project.config.json        # appid 占位 touristappid / 不校验合法域名（仅 dev）
├── pages/
│   ├── index/                 # F2 首页（tab）
│   ├── study/                 # F4 每日学习（tab）
│   ├── review/                # F6 间隔复习（tab）
│   ├── mistakes/              # F7 错题本（tab）
│   ├── stats/                 # F9 统计（tab）
│   ├── me/                    # F10 我的（tab）
│   ├── login/                 # F1 登录/注册
│   ├── setup-nickname/        # F1 新用户引导
│   ├── books/                 # F3 词库浏览与选择
│   ├── report/                # F8 AI 学习报告
│   └── about/                 # F10 关于（版权声明）
├── components/
│   ├── word-card/             # 单词卡片（翻面、多义项）— study/review 复用
│   ├── heatmap/               # 84 格热力图（4 档色阶）
│   ├── mastery-chart/         # canvas 2d 双折线
│   ├── ai-example-sheet/      # F5 底部弹层（加载/成功/降级三态）
│   ├── state-view/            # 空态/错误/加载 通用占位
│   └── skeleton/              # 骨架屏块
├── services/
│   ├── auth.js  book.js  study.js  review.js  mistake.js  ai.js  stats.js
└── utils/
    ├── request.js             # 唯一网络出口
    ├── storage.js             # token/userInfo 读写
    ├── date.js                # 日期格式化、近 N 天序列
    └── constants.js           # 错误码、分类映射、头像列表、超时常量
```

命名：目录 `kebab-case`，Page/Component 内方法与数据字段 `camelCase`。

---

## 3. `utils/request.js`（全局唯一网络出口）

```js
function request({ url, method = 'GET', data, timeout = 15000, auth = true }) → Promise<data>
```

必须处理的四件事，**且只在这一次实现**：

1. **baseURL**：`const BASE_URL = 'http://127.0.0.1:8080'`，集中一处，禁散落。
2. **token 注入**：`auth !== false` 时从 `storage.getToken()` 取，塞 `Authorization: Bearer <t>`。
   `auth: false` 仅供 `/auth/login`、`/auth/register`、`/auth/wx-login` 三个接口。
3. **`code !== "0"` 统一 reject**：
   ```js
   if (body.code !== '0') reject({ code: body.code, message: body.message })
   else resolve(body.data)
   ```
   → 页面只需 `try/catch`，`catch(e)` 里 `e.message` 可直接 toast。
4. **HTTP 401 / `code === '1006'`**：清 token + `wx.reLaunch('/pages/login/login')` + toast「登录已过期」。
   **用 `reLaunch` 不是 `navigateTo`**——登录页必须是唯一栈底，否则 401 后还能返回到已失效页面。
5. **网络失败**（`fail` 回调）：reject `{ code: 'NETWORK', message: '网络异常，请检查网络后重试' }`。

**并发防重**：request 层**不做**去重（同一接口不同页面各自需要），由按钮 `loading` 态在页面层控制。

---

## 4. 各页面设计

### 4.1 `pages/login/` — F1

- 两个入口：**微信一键登录（主）** / 账号密码（折叠备用）。
- `wx.login()` → `POST /auth/wx-login {code}`。
  - 成功 + `userInfo.isNewUser === true` → `wx.redirectTo('/pages/setup-nickname/...')`（带 nickname/avatar 预填）
  - 成功 + 老用户 → `wx.reLaunch('/pages/index/index')`
  - `1004` → toast「微信登录失败，请重试或使用账号密码登录」+ **自动展开**备用表单
- 账号密码：前端先校验（见 §7 校验规则），格式错就地提示**不发请求**。
- 注册成功后**自动登录**再跳首页。
- 按钮提交期间 `loading` 禁点。

### 4.2 `pages/setup-nickname/` — F1 引导

- 仅 `isNewUser` 进入；昵称预填 `userInfo.nickname`（后端生成的「微信用户xxxx」）可改。
- 6 个头像网格选 1，默认选中当前 `avatar`。
- 提交 `PUT /auth/profile {nickname, avatar}` → `reLaunch` 首页。
- 昵称 ≤20 字（`maxlength="20"`），空昵称不允许提交。

### 4.3 `pages/index/` — F2

- `onShow` **并行**发三个请求（`Promise.allSettled`，不是 `Promise.all`）：
  - `GET /study/plan` → 进度卡
  - `GET /study/streak` → 数据行
  - `GET /books` → 词书卡
- **`allSettled` 是硬要求**：prd「任一接口失败不阻塞其他卡」。用 `all` 会因一个失败全部白屏。
- 三态渲染：
  - **空态 A**：`books` 中无 `selected:true` → 进度卡替换为引导「先去选一本词书」
  - **空态 B**：`plan.total > 0 && plan.finished >= plan.total && !plan.checkedIn` → 「本词书已学完🎉」
  - **今日已完成**：`plan.checkedIn === true` → 主按钮灰「今日已完成，明天继续」
- 主按钮三态：`checkedIn` → 灰；`finished>0` → 「继续学习」；否则「开始学习」。
- 下拉刷新 `enablePullDownRefresh` 复用 `onShow` 逻辑。

### 4.4 `pages/books/` — F3

- 顶部 4 个分类 Tab：全部 / 四级 / 六级 / 考研 → `category` 分别传 `''` / `CET4` / `CET6` / `KAOYAN`。
- 卡片：名称、分类标签（中文映射）、`wordCount` 词、`description`（**`null` 时显示「暂无简介」**，两行截断）、操作按钮。
- 加入 → `POST /books/{id}/select`；已加入 → `DELETE`（先 `wx.showModal` 二次确认）。
- `2003`（词书已加入）当成功处理，刷新列表。

### 4.5 `pages/study/` — F4 核心

状态机（页面 `data.stage`）：

```
loading → empty(total=0) | studying → done(全部过完) → checkedIn
```

- `onLoad` 拉 `GET /study/plan` → `words[]` 作为队列，`cursor` 指向当前词。
- 当前词渲染 `<word-card>`；点击卡片 `flip` 切换正反面（CSS `transform: rotateY` + 200ms transition）。
- 「✓ 认识」→ `POST /study/record {wordId, bookId, known:true}`；「✗ 不认识」→ `known:false`。
- **上报成功才前进**（失败 toast 并停留当前词，避免数据不一致）。
- 打卡按钮：
  - 禁用条件 `finished < total`，文案「完成学习后打卡（{finished}/{total}）」
  - **必须用真实 `total`**，不能写死 20（`research/01` §2）
  - `total === 0` 时不显示按钮，显示「今日无待学内容」+ 去复习入口
- 打卡 → `POST /study/checkin`：
  - 成功 → `stage = 'checkedIn'`，展示打卡成功页（连续天数大数字 + 今日词数 + 错题数 + 查看错题）
  - `3001` → 视为已打卡（按钮变「已打卡 ✓」，toast「今日已打卡」）
  - `3002`/`3003` → toast 后端 message
- 中途退出：重新进入按 `plan` 接口重新拉（`learned:true` 的词后端已排除或标已学）。

### 4.6 `components/word-card/` — 卡片

props：

| prop | 类型 | 说明 |
|---|---|---|
| `word` | String | 单词 |
| `phonetic` | String | 音标（可空） |
| `translation` | String | 释义原文（含多义项） |
| `flipped` | Boolean | 是否翻面 |
| `showAi` | Boolean | 是否显示 AI 例句入口 |

- **多义项分条**：`translation` 是单字符串，按 `\n` 与 `；`/`;` 拆分 → `Array` 后 `wx:for` 逐条 `<view>`。
  拆分逻辑放 `utils/date.js` 同级新建 `utils/text.js`（保持单一职责）。
- 背面显示释义；`binderror` 空释义显示「暂无释义」。

### 4.7 `components/ai-example-sheet/` — F5 弹层

三态（`state`）：`loading` / `success` / `fallback`。

- 打开即 `POST /ai/example {wordId}`，`timeout: 15000`。
- 成功且 `degraded === false` → 渲染 `example` + `exampleTranslation` + `mnemonic`。
- 成功但 `degraded === true` → **顶部黄条**「AI 暂时不可用，展示内置例句」+ 显示 `example`（此时是内置例句）。
- 请求失败（`5001`/`5002`/超时）→ 同样进 `fallback` 态，展示 `word.example`；
  `example` 也为空 → 「该单词暂无例句」。
- **同词会话内复用**：`app.globalData.aiExampleCache = { [wordId]: {...} }`。
  再次打开同一词直接命中缓存，不发请求。缓存**仅在内存**，不落 Storage（避免脏数据跨会话）。
- 骨架屏用 `<skeleton>` 组件。

### 4.8 `pages/review/` — F6

- `onLoad` 拉 `GET /review/today` → `total` + `words[]` 队列。
- 卡片复用 `<word-card>`；默认不翻面，点一下翻面核对。
- 两按钮标注后果：「✗ 答错（退回第1天后）」/「✓ 答对（进入下一阶段）」
- 提交 `POST /review/record {wordId, correct}` → 成功**实时从队列移除**并推进 `cursor`。
- 队列空：
  - 初始就空 → 空态「今日没有待复习内容🎉」+ 去学习
  - 做完变空 → 「今日复习完成」页
  用 `data.initialTotal` 区分这两种。

### 4.9 `pages/mistakes/` — F7

- Tab：未掌握（`mastered=false`，带 `total` 角标）/ 已掌握（`mastered=true`）。
- 分页：`page` 递增，`onReachBottom` 加载更多；`list.length >= total` 停止。
- 条目三操作：
  - **AI 讲解** → `POST /ai/explain {mistakeId}`，`timeout: 30000`；条目内展开讲解区
  - **标记已掌握** → `POST /mistakes/{id}/mastered`，成功后从当前列表移除
  - **移除** → `wx.showModal` 确认 → `DELETE /mistakes/{id}`
- AI 讲解失败（`5001`/`5002`）→ 讲解区显示「AI 讲解暂时不可用，请稍后再试」，
  **标记已掌握按钮仍可用**（prd 硬要求）。
- 展开状态用 `data.expandedId` + `data.explainMap[mistakeId]`（缓存已拉讲解）。

### 4.10 `pages/report/` — F8

- 进入即调 `POST /ai/report`（无请求体），`timeout: 60000`。
- 全屏 loading「AI 正在分析你的学习数据…」。
- 成功 → 分段渲染 `report.overview` / `.weakness` / `.suggestion` + `generatedAt`。
- `5003` → 数据不足态「数据还太少，学几天再来生成报告」+ 继续学习按钮（**不调 AI**，后端直接拦）。
- 其他失败 → 「报告生成失败，请稍后重试」+ 重试按钮。
- `degraded` 为 true 时的表现：后端仍返回三段文本，前端照常渲染（报告降级由后端决定）。

### 4.11 `pages/stats/` — F9

- 两个请求：`GET /stats/heatmap`（12 周）/ `GET /stats/mastery`（30 天）。
- **热力图**：`components/heatmap` 用 84 个 `<view>` CSS Grid 排布，`level` 直接映射 4 个 CSS 类
  `hm-0`~`hm-3`（**不自己按 wordCount 分档**，后端已算好 `level`）。
  点击某格 → `wx.showToast({ title: `${date} 学习 ${wordCount} 词` })`，无跳转。
- **掌握度曲线**：`components/mastery-chart` 用 `<canvas type="2d" id="mastery">`。
  需 `wx.createSelectorQuery().select('#mastery').fields({node:true,size:true})` 拿真实 node
  （小程序 canvas 2d 必须走 node，不能用旧版 `createCanvasContext`）。
  - 双 Y 轴：左 `masteryRate`(0~100) 实线；右 `mistakeCount` 虚线
  - `dpr` 缩放避免模糊
- **空态判定**（关键，见 `research/01` §3.7）：
  ```
  heatmapEmpty = days.every(d => d.wordCount === 0)
  masteryEmpty = points.every(p => p.learnedCount + p.reviewCount + p.mistakeCount === 0)
  ```
  **绝不能用 `masteryRate === 0` 判断**——无数据时后端返回 100。

### 4.12 `pages/me/` — F10

- `onShow` 拉 `GET /auth/profile`。
- 用户卡：头像（`avatar_N` → 本地图片映射）、昵称、`@username`、`createTime`、`totalCheckInDays`。
- 昵称就地编辑：`bindblur` 时若变更则 `PUT /auth/profile {nickname}`。
- 更换头像：底部弹层 6 选 1 → `PUT /auth/profile {avatar}`。
- 退出登录：`wx.showModal` 确认 → 清 Storage + `globalData` → `reLaunch` 登录页。

### 4.13 `pages/about/` — 版权

必须含：版本号 + BSD 3-Clause 声明 + `KyleBing/english-vocabulary` 出处。
这是**验收硬项**（开源协议要求保留版权声明）。

---

## 5. 状态管理

| 数据 | 位置 | 生命周期 |
|---|---|---|
| `token` | Storage `vocab_token` + `globalData.token` | 登录写入，401/退出清除 |
| `userInfo` | Storage `vocab_user` + `globalData.userInfo` | 同上 |
| `aiExampleCache` | `globalData`（**仅内存**） | 会话内，杀进程即清 |
| 页面数据（列表、队列、loading） | 页面 `data` | 页面实例 |
| 时效数据（plan/streak/mistakes 数） | 页面 `data`，`onShow` 重拉 | 每次显示刷新 |
| 一次性数据（词书详情） | 页面 `data`，`onLoad` 拉 | 页面实例 |

`app.js` `onLaunch`：从 Storage 恢复 token/userInfo 到 `globalData`；
无 token 且当前非登录页 → 跳登录页。

---

## 6. app.json 关键配置

```json
{
  "pages": [ 11 个页面，login 放首位? 否 —— 首页放首位，登录页由 app.js 重定向 ],
  "tabBar": {
    "list": [
      {"pagePath":"pages/index/index","text":"首页"},
      {"pagePath":"pages/study/study","text":"学习"},
      {"pagePath":"pages/review/review","text":"复习"},
      {"pagePath":"pages/mistakes/mistakes","text":"错题"},
      {"pagePath":"pages/stats/stats","text":"统计"},
      {"pagePath":"pages/me/me","text":"我的"}
    ]
  },
  "window": { "navigationBarTitleText": "AI 单词打卡", ... }
}
```

- 六个 tabBar 页面**必须**是 `pages` 数组成员且**不能用 `wx.navigateTo` 跳转**（tab 只能用 `switchTab`）。
- tabBar 图标：一期可用纯文字（`iconPath` 可省），避免引入图片资源缺失导致编译失败。
  若要图标，用 81×81 PNG 放 `images/tabbar/`。**决策：一期不用图标图片，仅文字**，降低编译风险。

---

## 7. 校验规则（前端先校验，不发请求）

| 项 | 规则 | 提示 |
|---|---|---|
| 用户名 | `^[A-Za-z0-9_]{4,20}$` | 「用户名需为 4-20 位字母、数字或下划线」 |
| 密码 | ≥6 位且含字母+数字，无空白 | 「密码需至少 6 位，含字母和数字」 |
| 确认密码 | 与密码一致 | 「两次输入的密码不一致」 |
| 昵称 | 非空且 ≤20 字 | 「昵称不能为空」/「昵称最多 20 字」 |

与后端 `@Pattern`/`@Size` 逐条对齐（`research/01` §3.1）。

---

## 8. 验收方式：脚本驱动 + CLI 编译

无小程序单测框架，故分三层验证：

### 8.1 静态检查脚本 `scripts/verify_miniprogram.py`

用 Python 做 AST/正则级检查，覆盖「代码写错了」这类问题：

1. **页面完整性**：`app.json` 中每个 page 都有 `.js/.wxml/.wxss/.json` 四件套；
2. **禁用模式**：`pages/` 与 `components/` 内无 `wx.request(` 裸调用；无 `console.log(`；
3. **无硬编码地址**：除 `utils/request.js` 外无 `127.0.0.1` / `localhost` / `http://`；
4. **无第三方库**：无 `require('echarts')` 等，`miniprogram_npm/` 不存在；
5. **字段口径**：`services/*.js` 里出现的后端字段名在白名单内；
6. **tabBar**：`app.json` 的 tabBar 恰 6 项且路径都在 `pages` 内；
7. **空态判定**：`pages/stats/stats.js` 不含 `masteryRate === 0` 这类错误判据；
8. **打卡文案**：`pages/study/study.js` 不含写死的 `（n/20）` 或 `/20）`。

### 8.2 接口契约联调脚本 `scripts/verify_api_flow.py`

模拟小程序完整业务链路，直打 8080，验证**页面依赖的数据真实可用**：

```
注册 → 登录 → 选词书 → 拉 plan → 逐词 record → 打卡 → 拉 streak
→ 拉 review/today → 复习 record → 拉 mistakes → AI explain（或降级）
→ 标记已掌握 → 拉 stats/heatmap + mastery → 拉 profile → 改昵称/头像
→ AI example（或降级）→ AI report（或 5003）
```

断言每步 `code === "0"`（或预期错误码），并**断言响应字段集合与前端读取的字段一致**。
含降级分支：停 ai-service 时断言 `degraded:true` 或 `5002`。

### 8.3 CLI 编译检查

```bash
"/c/software/ide/微信web开发者工具/cli.bat" open --project <miniprogram 绝对路径>
```

CLI 打开项目会自动编译，能在 console 输出语法/配置错误。
（若 CLI 需要登录态而不可用，退化为「静态脚本 + 用户手动在 IDE 打开确认」，并在验收记录中注明。）

---

## 9. 需要用户提供/确认的项

| 项 | 用途 | 状态 |
|---|---|---|
| AppID `wxc99798ce418b7fce` | `project.config.json` | ✅ **已确认**，实测有效 |
| AppSecret | 后端 `code2Session` | ✅ **已确认有效**（后端口已配，`40029` 证明 appid+secret 均对） |
| 代码上传私钥 `private.wx*.key` | 仅 `miniprogram-ci` 上传用 | ✅ 已提供，已 gitignore |
| 组号（关于页） | 「软件工程课设 第 N 组」 | ⏳ 用户暂未定，先写占位 |

---

## 10. 风险与对策

| 风险 | 对策 |
|---|---|
| `<canvas type="2d">` 取 node 失败（基础库过低） | `wx.getSystemInfoSync().SDKVersion` 判断，低版本降级为「暂不支持图表」文案，不白屏 |
| tabBar 图标资源缺失导致编译失败 | 一期纯文字 tabBar，不配 `iconPath` |
| `description` 为 `null` 渲染成 "null" | 统一 `{{description \|\| '暂无简介'}}` |
| 空数据 `masteryRate=100` 误判为「已掌握」 | 用三计数全 0 判空态（§4.11） |
| 多词书导致 `total > 20`，打卡文案错 | 一律用 `{{finished}}/{{total}}` |
| 401 后用户能返回已失效页面 | 用 `wx.reLaunch` 清栈 |
| AI 请求挂住 60s 用户以为卡死 | 全屏 loading + 明确文案；超时由 request 层 `timeout` 兜底 |
| `wx.request` 并发超限（10 个） | 首页三请求 + 其他页面各自 <3，不触顶 |
| 开发期 http 域名未备案 | `project.config.json` 勾选「不校验合法域名」（**仅开发**，README 注明上线需 https+备案） |
