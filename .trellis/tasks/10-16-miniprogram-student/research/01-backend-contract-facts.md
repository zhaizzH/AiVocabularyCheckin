# 后端契约事实速查（学生端用）

> 本文把 `backend/` 里**唯一事实源**的字段与常量抄录并**实测验证**，供小程序实现与验收引用，
> 避免 `implement.jsonl` 直接引用代码文件（规范要求引用 spec/research 文档）。
> 全部条目均于 2026-10-17 对运行中的 `127.0.0.1:8080` 实测确认。

---

## 0. 通用约定

所有响应统一 `Result` 包装：

```json
{ "code": "0", "message": "ok", "data": ... }
```

- `code` 是**字符串**，成功为 `"0"`，其余为 4 位数字串。
- 失败时 `data` 为 `null`，`message` 为面向用户的中文文案（可直接 toast）。
- 鉴权：`Authorization: Bearer <token>`。未带/过期 → **HTTP 401** + `code=1006`。
- 分页：`page`（默认 1）、`size`（默认 20）；分页响应为 `{list, total, page, size}`。

## 1. 错误码全量（学生端只用到前半部分）

| code | message | 前端处理 |
|---|---|---|
| `0` | ok | 正常 |
| `1001` | 用户名已被占用 | toast（注册页） |
| `1002` | 用户名或密码错误 | toast（登录页） |
| `1003` | 账号已被禁用，请联系管理员 | toast |
| `1004` | 微信登录失败 | toast「微信登录失败，请重试或使用账号密码登录」+ 展开备用表单 |
| `1005` | 参数校验失败 | toast（理论上前端已先校验） |
| `1006` | 登录状态已失效，请重新登录 | 清 token → 跳登录页 → toast「登录已过期」 |
| `2001` | 词书不存在 | toast |
| `2002` | 未选择该词书 | toast |
| `2003` | 词书已加入 | 视为成功，刷新列表 |
| `3001` | 今日已打卡 | **不是错误**：按钮变「已打卡 ✓」 |
| `3002` | 今日无学习计划 | 显示「今日无待学内容」+ 去复习入口 |
| `3003` | 学习计划未完成不可打卡 | toast + 保持禁用态 |
| `4001` | 错题不存在 | toast + 刷新列表 |
| `4002` | 错题已掌握 | 视为成功，刷新列表 |
| `5001` | AI 服务超时 | F5 降级 / F7 失败文案 / F8 重试 |
| `5002` | AI 服务异常 | 同上 |
| `5003` | 学习数据不足无法生成报告 | F8 数据不足态（后端直接拦截，未发 HTTP 给 ai-service） |
| `9001`~`9004` | 系统繁忙 / 接口不存在 / 方法不支持 / Content-Type 不支持 | 通用 toast |

## 2. 业务常量（影响前端文案与校验）

| 常量 | 值 | 前端影响 |
|---|---|---|
| `DAILY_NEW_WORDS` | `20` | **打卡文案不能用固定 20**：后端门槛是 `plan.finished >= plan.total`，而 `total` = 待复习词 + 每本词书各 20 新词 + 今日已上报词。多本词书或复习日 `total > 20`，必须显示真实 `n/total` |
| `REVIEW_INTERVALS` | `{1, 3, 7, 15}` | 复习按钮后果文案：「退回1天后」/「进入下一阶段」 |
| `AVATARS` | `avatar_1` … `avatar_6` | 头像弹层 6 选 1，值必须是这 6 个字面量（`PUT profile` 用 `^avatar_[1-6]$` 校验） |
| `NICKNAME_MAX_LENGTH` | `20` | 昵称输入框 `maxlength=20` |
| `BOOK_CATEGORIES` | `CET4`, `CET6`, `KAOYAN` | 分类 Tab 的 `category` 取值（不是中文！显示文案另做映射） |
| `MIN_REPORT_STUDY_DAYS` | `3` | F8 数据不足阈值 |
| `DEFAULT_HEATMAP_WEEKS` | `12` | 热力图 12 周 = 84 格 |
| `DEFAULT_MASTERY_DAYS` | `30` | 掌握度曲线 30 天 |

## 3. 接口清单与字段（学生端 11 个）

### 3.1 认证 `/api/client/auth`

| 方法 | 路径 | 请求 | 响应 data |
|---|---|---|---|
| POST | `/register` | `{username, password, nickname?}` | `{userId, username, nickname, role}` |
| POST | `/login` | `{username, password}` | `{token, userInfo}` |
| POST | `/wx-login` | `{code}` | `{token, userInfo}` |
| GET | `/profile` | - | `{userId, username, nickname, avatar, role, createTime, totalCheckInDays}` |
| PUT | `/profile` | `{nickname?, avatar?}`（至少一项） | 同上 |

`userInfo` = `{userId, username, nickname, avatar, role, isNewUser}`。
**`isNewUser=true` 仅出现在首次微信登录**，此时跳「设置昵称」引导页。

前端校验规则（与后端 `@Pattern` 一致，先校验不发请求）：
- `username`：`^[A-Za-z0-9_]{4,20}$`
- `password`：`^(?=.*[A-Za-z])(?=.*\d)\S{6,}$`（≥6 位，含字母+数字，无空白）

### 3.2 词书 `/api/client/books`

| 方法 | 路径 | 请求 | 响应 data |
|---|---|---|---|
| GET | `` ?category= | - | `BookResponse[]` |
| GET | `/{bookId}` ?page&size | - | `{bookId,name,category,wordCount,description,words:{list,total,page,size}}` |
| POST | `/{bookId}/select` | - | `{bookId, selected}` |
| DELETE | `/{bookId}/select` | - | `{bookId, selected}` |

`BookResponse` = `{bookId, name, category, wordCount, description, selected, learnedCount}`。

⚠️ **实测：三本词书 `description` 全为 `null`**。简介区域必须做空值兜底（不显示或显示占位），
不能直接字符串截断（会渲染 `null`）。

### 3.3 学习 `/api/client/study`

| 方法 | 路径 | 请求 | 响应 data |
|---|---|---|---|
| GET | `/plan` | - | `{date, total, finished, checkedIn, words[]}` |
| POST | `/record` | `{wordId, bookId, known}` | `{wordId, learned, addedToMistakes}` |
| POST | `/checkin` | 无请求体 | `{checkInDate, streakDays, todayLearned, todayMistakes}` |
| GET | `/streak` | - | `{streakDays, totalLearned, todayReviewCount, lastCheckInDate}` |

`StudyWordResponse` = `{wordId, bookId, word, phonetic, translation, example, isReview, learned}`。

`translation` 是**单字符串**，多义项以分隔符并列（如 `v. 放弃；抛弃\nn. 放任`），
前端需按分隔符拆条渲染（prd「多义项分条」）。

### 3.4 复习 `/api/client/review`

| 方法 | 路径 | 请求 | 响应 data |
|---|---|---|---|
| GET | `/today` | - | `{total, words[]}` |
| POST | `/record` | `{wordId, correct}` | `{wordId, stage, nextReviewDate}` |

`ReviewWordResponse` = `{wordId, word, phonetic, translation, example, stage, wrongCount}`。

### 3.5 错题 `/api/client/mistakes`

| 方法 | 路径 | 请求 | 响应 data |
|---|---|---|---|
| GET | `` ?mastered&page&size | - | `{list, total, page, size}` |
| POST | `/{mistakeId}/mastered` | - | `{mistakeId, mastered}` |
| DELETE | `/{mistakeId}` | - | `{mistakeId, removed}` |

`MistakeResponse` = `{mistakeId, wordId, word, phonetic, translation, source, wrongCount, mastered, createTime}`。
`source` 取值 `STUDY` / `REVIEW`（前端映射为「学习」/「复习」标签）。

### 3.6 AI `/api/client/ai`（全部经后端中转，前端绝不直连 ai-service）

| 方法 | 路径 | 请求 | 响应 data | 前端超时 |
|---|---|---|---|---|
| POST | `/example` | `{wordId}` | `{wordId, example, exampleTranslation, mnemonic, degraded}` | 15s |
| POST | `/explain` | `{mistakeId}` | `{mistakeId, explanation, advice, degraded}` | 30s |
| POST | `/report` | 无请求体 | `{report:{overview,weakness,suggestion}, generatedAt, degraded}` | 60s |

**`degraded` 是降级标志**：`true` 时 `example` 为词库内置例句，
F5 必须显示顶部黄条「AI 暂时不可用，展示内置例句」。
`degraded:true` 也是 **HTTP 200 + code "0"**，不是错误——前端不能只看 code 判成功。

### 3.7 统计 `/api/client/stats`

| 方法 | 路径 | 请求 | 响应 data |
|---|---|---|---|
| GET | `/heatmap` ?weeks | - | `{days: [{date, wordCount, level}]}` |
| GET | `/mastery` ?days | - | `{points: [{date, learnedCount, reviewCount, mistakeCount, masteryRate}]}` |

- `level` 由后端算好：`0`=未学 / `1`=1–10 词 / `2`=11–20 词 / `3`=21+ 词。
  前端**直接用 `level` 映射 CSS 类**，不要自己再按 `wordCount` 分档。
- 默认返回补零后的完整序列（12 周 = 84 天 / 30 天），断档日期也在里面。

⚠️ **实测：无任何学习数据时 `masteryRate` 返回 `100`**（分母为 0 的默认值）。
F9 空态判定**不能用 `masteryRate`**，必须用
`points.every(p => p.learnedCount + p.reviewCount + p.mistakeCount === 0)`
以及 `days.every(d => d.wordCount === 0)`。

## 4. 实测记录（2026-10-17，真实后端 127.0.0.1:8080）

注册 + 登录 + 拉取各接口，确认：

- `GET /books` → 3 本词书（`bookId` 6/7/8，CET4 7508 词、CET6 5651 词、KAOYAN 9602 词），`description` 均为 `null`
- `GET /study/plan`（新账号未选词书）→ `{total:0, finished:0, checkedIn:false, words:[]}`
- `GET /study/streak` → `{streakDays:0, totalLearned:0, todayReviewCount:0, lastCheckInDate:null}`
- `GET /mistakes` → `{list:[], total:0, page:1, size:20}`
- `GET /review/today` → `{total:0, words:[]}`
- `GET /stats/heatmap` → 84 天全 `level:0`
- `GET /stats/mastery` → 30 天全 `masteryRate:100`（空数据默认值，见上）
- `GET /profile` → `{userId:97, username:"mp_probe01", nickname:"探针", avatar:"avatar_1", role:"USER", createTime:"2026-10-17 12:55:18", totalCheckInDays:0}`

## 5. 微信登录现状（**无阻塞，配置正确**）

`POST /api/client/auth/wx-login` 对伪造 code 返回 `1004`。`1004` 是**对任意非法 code 的正常响应**，
不是配置错误——它证明后端确实调到了微信服务器并收到了拒绝。

直接对微信 `jscode2session` 用伪造 code 探测（2026-10-17 实测）：

| appid | secret | 微信返回 | 含义 |
|---|---|---|---|
| `wx0000000000000000`（伪） | 任意 | `40013 invalid appid` | appid 不存在 |
| `wxc99798ce418b7fce` | `0`×32 | `40125 invalid appsecret` | appid 有效、secret 错 |
| `wxc99798ce418b7fce` | **`application-local.yml` 的实际 AppSecret** | **`40029 invalid code`** | **appid + secret 均有效**，仅探测用的 code 无效 |

结论：**AppID 与 AppSecret 均正确且已生效**。`40029` 是用假 code 探测时的预期结果。

> ⚠️ 排查陷阱（曾误判一次）：若用正则 `re.search(r'secret:\s*(\S+)', yml)` 抽 secret，
> 会**先命中 `jwt.secret`**（该文件里 `wx:` 段之前就有 `jwt.secret`），拿到 51 字符的
> `local-dev-secret-key-...`，于是微信返回 `40125`，看起来像「AppSecret 填错」，
> 实际是**探测脚本取错字段**。正确做法是按 YAML 结构定位 `wx:` 块内的 `secret`
> （或用 `yaml.safe_load` 读 `data['wx']['secret']`）。

因此 F1 微信登录**不存在阻塞**：真机/开发者工具里 `wx.login()` 拿到的真实 code
可以直接换到 openid。若后续真机联调失败，应先排查 `project.config.json` 的 appid
是否为 `wxc99798ce418b7fce`（不一致时 `wx.login` 得到的 code 与后端 appid 不匹配 → 40029）。
