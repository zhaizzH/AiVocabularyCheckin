# 管理端接口实测事实（2026-10-17）

> 所有条目均为 **对 `127.0.0.1:8080` 真实请求的返回**，不是读文档推断。
> 探测脚本：`scripts/probe_admin_api.py`、`scripts/probe_admin_edge.py`。
> 用途：`design.md` 的类型定义与错误处理必须以本文为准。

---

## 0. 前置：没有 ADMIN 账号

数据库 346 个用户**全部 `role='USER'`**，没有管理员账号，管理端无法登录。

新增 `scripts/create-admin.py`（可重复执行）：

```bash
python scripts/create-admin.py                          # 新建 admin / Admin@12345
python scripts/create-admin.py --promote mp_probe01     # 把已有账号提权，不改密码
```

密码必须 **BCrypt 强度 10**（与 `AuthServiceImpl.BCRYPT_STRENGTH` 一致）——
后端用 `BCryptPasswordEncoder.matches()` 校验，写明文或 MD5 一律登录失败。

已创建：`admin` / `Admin@12345`（userId 444）。

---

## 1. 登录与角色

`POST /api/client/auth/login`（管理端与小程序**共用**此接口，靠 role 区分）

```json
{ "code": "0", "message": "ok",
  "data": { "token": "eyJhbGciOiJIUzM4NCJ9...",
            "userInfo": { "userId": 444, "username": "admin", "nickname": "管理员",
                          "avatar": null, "role": "ADMIN" } } }
```

- **`data.userInfo.role` 就是前端判断能否进管理端的唯一依据**（`"ADMIN"` / `"USER"`）。
- token 是标准 JWT，前端只需 `Authorization: Bearer <token>`。

### 403 与 401 的区别（实测）

| 场景 | HTTP | code | message |
|---|---|---|---|
| 无 token / token 失效 | **401** | `1006` | 登录状态已失效，请重新登录 |
| 普通用户（role=USER）访问 `/api/admin/**` | **403** | `1007` | 无权访问该资源 |
| 用户已被禁用（旧 token 仍在手） | **401** | `1003` | 账号已被禁用，请联系管理员 |

**关键**：`1007` 是 **HTTP 403 且 code 非 "0"**。`utils/request.ts` 不能只按
HTTP 状态判断，必须**先看 `code`**，再按 code 分流：

- `1006` / `1003` → 清 token → 跳登录页（这两种是「你的登录态不可用了」）
- `1007` → **不要清 token**（登录态是好的，只是没权限）→ 提示「该账号无管理员权限」
  并跳回登录页（管理端所有页面都要 ADMIN，留着也没用）

> 这是本项目最容易写错的一处：把 1007 当 1006 处理会让管理员被莫名其妙踢下线，
> 且没有任何解释；把 1006 当普通错误处理则会造成「点了没反应」。

---

## 2. `GET /api/admin/books` —— 词书列表

**`data` 是裸数组**（不是分页包装）。实测 3 本：

```json
{ "bookId": 6, "name": "四级核心词汇", "category": "CET4",
  "wordCount": 7508, "description": null, "createTime": "2026-10-16 18:39:54" }
```

| bookId | name | category | wordCount |
|---|---|---|---|
| 6 | 四级核心词汇 | CET4 | 7508 |
| 7 | 六级核心词汇 | CET6 | 5651 |
| 8 | 考研核心词汇 | KAOYAN | 9602 |

元素字段集合 = `bookId / category / createTime / description / name / wordCount`
（**无** `selected` / `learnedCount`——那是用户端 §2.1 的字段）。
`description` 实测为 `null`，TS 类型须写 `string | null`。
`createTime` 是**字符串** `"yyyy-MM-dd HH:mm:ss"`，不是时间戳，不要 `new Date()` 直接解析（Safari 会 NaN）。

---

## 3. `GET /api/admin/words?bookId=&keyword=&page=&size=` —— 单词分页

`data` 是**分页包装**（与 books 不同！）：

```json
{ "list": [ { "wordId": 5, "word": "abruptly", "phonetic": "ə'brʌptlɪ",
              "translation": "adv. 突然地", "example": "The path ends off abruptly." } ],
  "total": 7508, "page": 1, "size": 3 }
```

- 分页字段 = `list / total / page / size`（**不是** `records` / `pages` / `current`）。
- 元素字段 = `wordId / word / phonetic / translation / example`。
- `bookId` **可选**：不传时返回全书库（`total` = 22761，实测）。
- `keyword` 是模糊匹配，`keyword=aban` → `total=2`，第一条 `word="abandon"`。
- `phonetic` / `translation` / `example` 均可能为 `null`（种子数据里有空字段）。

---

## 4. 单词 CRUD

| 操作 | 请求 | 响应 `data` |
|---|---|---|
| 新增 | `POST /api/admin/words` `{bookId, word, phonetic, translation, example}` | `{wordId, word, phonetic, translation, example}` |
| 修改 | `PUT /api/admin/words/{wordId}` 字段均可选 | 同上（更新后完整对象） |
| 删除 | `DELETE /api/admin/words/{wordId}` | `{wordId, deleted: true}` |

实测 **`wordCount` 会同步变化**：新增后 7508 → 7509，删除后 → 7508。
所以单词弹窗操作完必须**刷新词书列表**，否则父页面数字是旧的。

错误码（都是 **HTTP 200 + code 非 "0"**）：

| 场景 | code | message |
|---|---|---|
| 缺 `word` | `1005` | 单词不能为空 |
| 改不存在的词 | `1005` | 单词不存在：99999999 |

---

## 5. `GET /api/admin/users?keyword=&page=&size=` —— 用户分页

分页包装，元素字段：

```json
{ "userId": 97, "username": "mp_probe01", "nickname": "探针", "role": "USER",
  "status": 1, "totalCheckInDays": 0, "createTime": "2026-10-17 12:55:18" }
```

- `status`：**数字 `1`/`0`**，不是字符串也不是布尔。
- `totalCheckInDays` 已由后端算好，前端不重算。
- `keyword` 搜索用户名（实测 `keyword=admin` 命中 2 条）。
- **`status` 查询参数无效**：传 `?status=0` 仍返回 `status:1` 的用户 → 后端忽略该参数。
  前端**不要**做「按状态筛选」的 UI，否则点了没反应。

---

## 6. `PUT /api/admin/users/{userId}/status` —— 禁用/启用

请求 `{ "status": 0 }`（0 禁用 / 1 启用），响应 `{userId, status}`。

实测的跨端效果（这是 PRD 的验收项）：

| 操作 | 结果 |
|---|---|
| 禁用用户后，该用户用**旧 token** 调 `/api/client/books` | **401 + 1003**「账号已被禁用」 |
| 禁用后该用户重新登录 | **200 + 1003**「账号已被禁用，请联系管理员」 |
| 启用后 | 恢复正常 |
| **禁用 ADMIN 账号** | `1005`「不允许修改管理员账号状态」 |

**所以用户管理页里 ADMIN 行的禁用按钮必须 `disabled`**，否则用户点了只会收到
一个错误 toast，体验差且看起来像 bug。前端 `disabled` 是**主动预防**，不是重复校验。

---

## 7. `POST /api/admin/books/import` —— JSONL 导入

`multipart/form-data`，字段 `file` + `name` + `category`。
响应 `data: { bookId, success, skipped }`。

实测：4 行里 2 好 2 坏 → `{"bookId": 20, "success": 2, "skipped": 2}`。

| 场景 | code | message |
|---|---|---|
| `category` 不在 CET4/CET6/KAOYAN | `1005` | category 仅支持 CET4 / CET6 / KAOYAN |
| 整个文件无任何合法行 | `2004` | 导入文件格式错误 |

**`2004` 时不会留下空词书**（后端延迟到第一行合法数据才建词书，有专门注释说明）。

### 我踩过的探测脚本坑（记下来避免重犯）

multipart 的结尾如果是 `--boundary--\r\n` 但**最后一段 body 少了 `\r\n`**，
后端会把文件流读坏 → 返回 `9001 系统繁忙` 而不是 `2004`。
我第一次写探测脚本就漏了这个 CRLF，误判成后端缺陷。**不是后端 bug**。

---

## 8. `DELETE /api/admin/books/{bookId}` —— 删除词书

响应 `{bookId, deleted: true}`。**连带逻辑删除该书下全部单词**。
不存在 → `2001`「词书不存在」。

---

## 9. `GET /api/admin/monitor/ai-stats` —— 监控

```json
{ "totalCalls": 85, "successRate": 92.9, "degradedCount": 6, "registeredUsers": 349,
  "features": [ { "feature": "example", "total": 47, "success": 45, "degraded": 2,
                  "timeout": 0, "error": 2, "avgCostMs": 1049 } ],
  "recentEvents": [ { "time": "2026-10-17 13:37:55", "feature": "report",
                      "costMs": 7, "reason": "ERROR", "result": "抛 5002" } ] }
```

- `totalCalls` **含降级**（否则成功率虚高）。
- `successRate` 是**数字**（百分数，保留 1 位），无调用时为 `100`。前端不要乘 100。
- `features[]` 固定 3 项（example / explain / report），但**不要假设顺序**。
- `recentEvents[]` 最新在前，最多 100 条；`reason` ∈ `TIMEOUT` / `ERROR` / `CIRCUIT_OPEN`。
- 数据是**进程内内存计数器**，重启清零 → 页面应注明「服务启动以来」。

---

## 10. CORS

`application.yml`：`cors.allowed-origins = ${CORS_ALLOWED_ORIGINS:http://localhost:5173,http://127.0.0.1:5173}`

→ **Vite 默认 5173 端口刚好在白名单里**，开发期无需改后端。
若换端口（如 5174）会直接跨域失败，需改 `application.yml` 或设环境变量。

---

## 11. 前端必须遵守的推论

1. `code` 是**字符串** `"0"`，判断用 `=== "0"`，不能用 `=== 0`。
2. **`Result<T>` 包装的 `data` 既可能是对象、数组，也可能是 `null`**——TS 类型要写 `T | null`，取用前判空。
3. `books` 裸数组 / `words` `users` 分页包装 —— 两种形状必须分别建模，不能统一成一个「列表响应」。
4. 单词 CRUD 成功后必须刷新**词书列表**（`wordCount` 变了）。
5. ADMIN 行禁用按钮 `disabled`。
6. `1006`/`1003` 清 token 跳登录；`1007` 提示无权限跳登录但**不清 token**（清不清都跳，但语义不同，且清 token 会让「刚登录就看到无权限」的排查变困难）。
