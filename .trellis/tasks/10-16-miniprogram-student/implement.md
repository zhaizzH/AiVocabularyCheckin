# miniprogram 学生端 · 实施计划

> 配套 `design.md`。按阶段推进，每阶段有**可执行的验收命令**，不靠肉眼判断。
> 红线：页面不裸 `wx.request`；前端不直连 `ai-service`；不引第三方库；不提交 `console.log`。

---

## 0. 与规范的偏离声明（必须先读）

`.trellis/spec/frontend/directory-structure.md` 的目录布局只列了 5 个页面
（`index / study / review / mistakes / stats`），但 `prd.md` 要求**六**个 tabBar
（首页/学习/复习/错题/统计/**我的**），另需登录、词库、报告、引导、关于共 11 个页面。

**偏离是必要的**：规范中的布局是示意，prd 是功能事实源，且新增页面完全遵循同一命名与分层约定。
本任务按 `design.md §2` 的 11 页实施。此偏离记录在此，供后续修订规范时补齐
`me`/`login`/`books`/`report` 等条目。

---

## 1. 阶段划分

| 阶段 | 内容 | 产出 | 验收 |
|---|---|---|---|
| S1 | 骨架与网络层 | `app.*`、`utils/*`、`services/*`、`project.config.json` | 静态脚本 §4.1 全绿 |
| S2 | 认证链路 F1 | `login`、`setup-nickname` + auth service | 真实注册/登录通过，401 清栈 |
| S3 | 首页与词库 F2/F3 | `index`、`books` | 三卡并行、空态 A/B、选书生效 |
| S4 | 学习与打卡 F4/F5 | `study`、`word-card`、`ai-example-sheet` | 学满→打卡、降级黄条 |
| S5 | 复习 F6 | `review` | 队列实时移除、两种空态 |
| S6 | 错题与 AI 讲解 F7 | `mistakes` | 分页、展开讲解、降级仍可标掌握 |
| S7 | 报告 F8 | `report` | 成功 / 5003 数据不足 |
| S8 | 统计 F9 | `stats`、`heatmap`、`mastery-chart` | 84 格、4 档色、canvas 双折线、空态判据 |
| S9 | 我的与关于 F10 | `me`、`about` | 改昵称/头像、退出、版权 |
| S10 | 全链路验收 | `scripts/verify_miniprogram.py`、`scripts/verify_api_flow.py` | 两脚本全绿 + CLI 编译无错 |

每阶段完成即跑一次静态检查，不积压到末尾。

### 阶段状态

| 阶段 | 状态 | 备注 |
|---|---|---|
| S1 骨架 + 网络层 | ✅ 完成 | `87fc561`；76 项后端断言全绿 |
| S2 登录链路 F1 | ✅ 完成 | `87fc561`；含微信首登昵称引导 |
| S3 首页 F2 + 词库 F3 | ✅ 完成 | `5ac4950`；42 项断言 |
| S4 学习 F4 + 打卡 F5 | ✅ 完成 | `7b9423c`；修 advance() 竞态 |
| S5 复习 F6 | ✅ 完成 | `d7fd471`；修 PARAM_INVALID 拼写缺陷 |
| S6 错题本 F7 + AI 讲解 | ✅ 完成 | `da0be3a`；26 项后端断言 |
| S7 AI 报告 F8 | ✅ 完成 | `96b8450`；四种终态 |
| S8 统计 F9 | ✅ 完成 | `b1a1aa7`；手绘 canvas 双折线 |
| S9 我的 + 关于 F10 | ✅ 完成 | `636bd49`；含 BSD 版权门禁 |
| S10 全链路验收 | ✅ 完成 | 328 项断言全绿；`cli.bat preview` 编译零错误 |

**S10 阶段发现：PRD 的 6 tab 与微信平台限制冲突**

编译器报 `app.json: ["tabBar"]["list"] 不能超过 5 项`。`custom: true` 自定义
tabBar 也受同一限制（`list` 仍需声明 2–5 项）。已与用户确认取舍：

- 去掉「统计」tab → 底部导航为 首页 / 学习 / 复习 / 错题 / 我的
- 统计页改为普通页，入口两处：首页快捷入口 + 我的页「学习统计」
- `index.js#goStats` 与 `me.js#onOpenStats` 都改用 `navigateTo`
- `stats.js#goStudy` 仍用 `switchTab`（学习仍是 tab 页）

为此新增一条静态门禁 `check_page_reachable()`：非 tab 页必须在登记表
里列出的页面中找到跳转入口。页面从 tabBar 移出后若入口被删，它就成了
死页——编译通过、单页测试通过、用户永远打不开，只有静态扫入口能发现。
牙齿验证：同时删掉两处 stats 入口 → 门禁变红 → 恢复 → 变绿。

**当前验证规模（真实后端 + 真实 DeepSeek）**

| 套件 | 项数 | 覆盖 |
|---|---|---|
| `verify_miniprogram.py` | 16 | 静态红线（分层、错误码拼写、tab 跳转、版权、空态判据） |
| `verify_api_flow.py` | 76 | 契约字段、错误码、边界 |
| `verify_review_flow.py` | 18 | SM-2 间隔、错题联动 |
| `verify_mistake_flow.py` | 26 | 错题收录、AI 讲解、4001/4002 |
| `verify_report_flow.py` | 11 | 5003 前置校验、真实报告生成 |
| `verify_stats_flow.py` | 18 | 热力图分档、掌握度曲线、空态 |
| `verify_page_flow.js` | 98 | 页面方法直调（含 canvas 绘制序列） |
| `verify_e2e_flow.py` | 49 | F1→F10 串联 |
| `verify_degraded_flow.py` | 16 | ai-service 停机降级（手动） |
| **合计** | **328** | 一键：`python scripts/verify_all.py` |

**S1 已交付**：
- `app.js` / `app.json`（11 页 + 6 tabBar 纯文字）/ `app.wxss`（仅设计 token + 原子类）
- `utils/{constants,request,storage,date,text}.js`
- `services/{auth,book,study,review,mistake,ai,stats}.js`（7 个，全部 JSDoc 标注返回结构）
- `project.config.json`（appid = `wxc99798ce418b7fce`，`urlCheck:false` 仅开发期）
- 11 个页面四件套骨架（可编译，业务留待 S2–S9）
- `scripts/verify_miniprogram.py`（13 项静态检查，已反向验证 7 个违规样本均被捕获）
- `scripts/verify_api_flow.py`（76 项断言，跑真实后端）

**S1 阶段发现并修正的三件事**：
1. `research/01` 原判定「wx.secret 填错导致 wx-login 1004」是**误判**——
   探测脚本用正则抓 `secret:` 时命中了 `jwt.secret`（51 字符）而非 `wx.secret`（32 字符）。
   实测正确取值后微信返回 `40029 invalid code`（= appid+secret 均有效）。
   **F1 微信登录无阻塞。**
2. 静态检查器初版有两个假失败：把 `constants.js` 里的 `BASE_URL` 判成违规；
   把 `request({url, method, data})` 的**本地调用选项**当成后端字段。
   已改为「地址白名单 = constants.js」「只扫内联 `data:{}` 字面量」。
3. `implement.md §7` 第 3 条命令的 `grep -v` 过滤了 `utils/request.js`，
   但地址真正的归属是 `utils/constants.js`——命令会漏掉「request.js 里又写一份字面量」的情况。已修正。

---

## 2. S1 详细：网络层是最关键的 40 行

**`utils/request.js` 必须一次写对**——它是唯一出口，错了会让所有页面一起错。

要点清单（对应 `design.md §3`）：

- [ ] `BASE_URL` 只有一处定义
- [ ] `auth !== false` 才注入 token；三个公开接口显式传 `auth:false`
- [ ] `body.code !== '0'` → `reject({code, message})`
- [ ] **HTTP 401 或 `code === '1006'`** → 清 token → `wx.reLaunch` 登录页 → toast「登录已过期」
- [ ] `fail` 回调 → `reject({code:'NETWORK', message:'网络异常…'})`
- [ ] 支持逐请求 `timeout`（AI 三接口 15/30/60 秒）
- [ ] **不在 request 层弹 toast**（页面决定怎么提示）

**陷阱**：`wx.request` 的 `timeout` 默认 60s，且 `fail` 回调里 `errMsg` 形如
`request:fail timeout`。要按 `errMsg.indexOf('timeout')` 区分超时与断网，
否则 AI 超时会被提示成「网络异常」。

---

## 3. 各阶段验收命令

### 3.1 静态检查（每阶段后执行）

```bash
cd /c/workspace/class/AiVocabularyCheckin
PYTHONIOENCODING=utf-8 python miniprogram/scripts/verify_miniprogram.py
```

期望输出 `✓ N 项检查全部通过`，退出码 0。

### 3.2 真实接口联调（S2 之后每阶段后执行）

```bash
PYTHONIOENCODING=utf-8 python miniprogram/scripts/verify_api_flow.py
```

要求后端 `127.0.0.1:8080` 在跑。覆盖 `design.md §8.2` 的完整链路。

### 3.3 编译检查（S10）

```bash
"/c/software/ide/微信web开发者工具/cli.bat" open \
  --project "C:\workspace\class\AiVocabularyCheckin\miniprogram"
```

CLI 若因需登录态不可用，退化为人工在 IDE 打开，并在 `research/02-verification.md` 记录原因。

---

## 4. 依赖顺序与并行度

```
S1 ──┬─▶ S2 ──▶ S3 ──▶ S4 ──▶ S5
     │                      └─▶ S6 ──▶ S7
     └─────────────────────────────▶ S8 ──▶ S9
                                              └─▶ S10
```

- S1 是所有阶段的前置，不可跳过。
- S4 的 `ai-example-sheet` 与 S6 的 AI 讲解都依赖 AI 链路，但**互不阻塞**（各自独立弹层/展开区）。
- S8 统计只依赖 S1，可与 S2–S7 并行；但建议放后面，因为 canvas 调试耗时不可控。

---

## 5. 陷阱表（踩过或预判的）

| # | 陷阱 | 症状 | 对策 |
|---|---|---|---|
| 1 | 打卡文案写死 `n/20` | 多词书/复习日显示 `25/20` | 一律用 `plan.finished`/`plan.total`（`research/01` §2） |
| 2 | 用 `masteryRate === 0` 判空态 | 空账号被判定「已掌握」，图表画成满格 | 用三计数全 0 判据（`design.md §4.11`） |
| 3 | 自行按 `wordCount` 分档热力色 | 与后端 `level` 不一致，1 词和 10 词同色错乱 | 直接用后端 `level` 映射 CSS 类 |
| 4 | `description` 为 `null` 直接插入 | 页面显示字符串 `null` | `{{description \|\| '暂无简介'}}` |
| 5 | 首页用 `Promise.all` | 一个接口挂了整页白屏 | `Promise.allSettled` |
| 6 | 401 用 `navigateTo` | 用户能返回已失效页面，反复 401 | `wx.reLaunch` 清栈 |
| 7 | 复习页不区分「初始空」与「做完空」 | 做完显示「今日没有待复习内容」bug 感强 | 用 `initialTotal` 区分（`design.md §4.8`） |
| 8 | tabBar 页面用 `navigateTo` 跳转 | 报错 `can not navigateTo a tabbar page` | tab 页一律 `wx.switchTab` |
| 9 | `canvas type="2d"` 用旧 API 取上下文 | 画布空白，无报错 | 必须 `createSelectorQuery().fields({node:true})` 拿 node |
| 10 | 学习页 `record` 失败仍前进 | 数据与后端不一致，打卡时 `finished` 对不上 | 上报成功才 `cursor++` |
| 11 | AI 请求无 loading，60s 以为卡死 | 用户重复点击放大请求 | 全屏 loading + 按钮禁用 |
| 12 | `degraded:true` 当失败处理 | 明明有内置例句却报错 | `degraded` 是成功的一种（`design.md §4.7`） |
| 13 | 六个 tab 页面未在 `pages` 数组声明 | 编译报 `page not found` | `app.json` 的 `pages` 必须含全部 tabBar 路径 |
| 14 | `translation` 多义项整块渲染 | 与 prd「分条」不符 | `utils/text.js` 拆分后 `wx:for` |
| 15 | Storage 里存 key 前缀不一致 | 重启后登录态丢失 | 常量集中在 `utils/constants.js`（`vocab_token`/`vocab_user`） |

---

## 6. 完成定义（DoD）

- [ ] 11 个页面全部实现，四件套齐全并在 `app.json` 注册
- [ ] 六个 tabBar 可切换，复习/错题角标显示真实数量
- [ ] F1 账号密码链路端到端通过；微信登录按真实实现（secret 到位后复验）
- [ ] F2 三卡并行 + 空态 A/B + 下拉刷新
- [ ] F3 分类筛选、加入/移出、`description` 空值兜底
- [ ] F4 学习队列 + 上报 + 打卡（成功/3001/3002/3003 四分支）
- [ ] F5 AI 例句三态（加载/成功/降级黄条）+ 会话缓存
- [ ] F6 复习队列 + 实时移除 + 两种空态
- [ ] F7 错题分页 + AI 讲解展开 + 降级仍可标掌握
- [ ] F8 报告成功 + 5003 数据不足 + 重试
- [ ] F9 84 格热力图 4 档色 + canvas 双折线 + 空态判据正确
- [ ] F10 资料编辑 + 头像 6 选 1 + 退出登录
- [ ] 关于页含 BSD 3-Clause 与词库出处
- [ ] `verify_miniprogram.py` 与 `verify_api_flow.py` 双绿
- [ ] 无 `console.log`、无裸 `wx.request`、无硬编码地址（除 request.js）、无第三方库
- [ ] `research/02-verification.md` 记录实测证据（含降级态证据）

---

## 7. 红线自检命令

⚠️ **不要用 `! grep ... | grep -v ...` 这种写法**——管道的退出码来自最后一个命令，`!` 取反的是
整条管道，判定会被前段匹配污染（实测：只有 `utils/request.js` 有地址时本应通过却报失败）。
下面用「先取输出、再判空」的写法，语义明确。

```bash
cd /c/workspace/class/AiVocabularyCheckin/miniprogram

# 1. 页面/组件内不得有裸 wx.request
out=$(grep -rn "wx\.request" pages/ components/ 2>/dev/null)
[ -z "$out" ] && echo "✓ 无裸 wx.request" || { echo "✗ 发现裸 wx.request:"; echo "$out"; }

# 2. 不得有 console.log
out=$(grep -rn "console\.log" pages/ components/ utils/ services/ 2>/dev/null)
[ -z "$out" ] && echo "✓ 无 console.log" || { echo "✗ 发现 console.log:"; echo "$out"; }

# 3. 除 utils/constants.js 外不得出现后端地址
#    BASE_URL 的唯一定义处是 utils/constants.js，request.js 只能 require 引入，
#    不得自己写第二份字面量——否则「改地址只改一处」的承诺就破了。
out=$(grep -rn "127\.0\.0\.1\|localhost\|https\?://" pages/ components/ utils/ services/ 2>/dev/null \
        | grep -v "^utils/constants\.js:")
[ -z "$out" ] && echo "✓ 地址仅存在于 utils/constants.js" || { echo "✗ 发现散落地址:"; echo "$out"; }

# 4. 不得引用第三方图表库、不得有 npm 构建产物
out=$(grep -rniE "require\(['\"](echarts|ucharts|@antv|f2)" pages/ components/ utils/ services/ 2>/dev/null)
[ -z "$out" ] && echo "✓ 无第三方图表库" || { echo "✗ 发现第三方库:"; echo "$out"; }
[ ! -d miniprogram_npm ] && echo "✓ 无 miniprogram_npm" || echo "✗ 存在 miniprogram_npm"
```

**关键：判定用 `[ -z "$out" ]`，不用 `!` 取反管道**。这些检查已封装进
`scripts/verify_miniprogram.py`（`design.md §8.1`），以脚本结果为准；上面的命令用于手工复核。
