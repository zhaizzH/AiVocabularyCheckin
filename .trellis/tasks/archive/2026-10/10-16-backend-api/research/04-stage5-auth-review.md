# 阶段 5 复核：认证模块（Auth）

> 对应闸口：**5 个 auth 接口自测通过**。
> 结论：**通过**。`mvn -o clean test` 118 全绿（连跑 3 次稳定）；真实 MySQL + 真实微信 API 冒烟 38/38 全过。

---

## 1. 本阶段交付范围

| 契约 | 接口 | 实现 |
|---|---|---|
| §1.1 | `POST /api/client/auth/register` | `AuthController#register` → `AuthServiceImpl#register` |
| §1.2 | `POST /api/client/auth/login` | `AuthController#login` → `AuthServiceImpl#login` |
| §1.3 | `POST /api/client/auth/wx-login` | `AuthController#wxLogin` → `AuthServiceImpl#wxLogin` |
| §1.4 | `GET /api/client/auth/profile` | `AuthController#profile` → `AuthServiceImpl#profile` |
| §1.5 | `PUT /api/client/auth/profile` | `AuthController#updateProfile` → `AuthServiceImpl#updateProfile` |

外加 §8.7「管理员禁用 → 该用户 token 立即失效」——该能力不在某个接口里，而是落在拦截器每个受保护请求上。

---

## 2. 真正的难关：`UserStatusValidator` 从「门面」变「真实现」

阶段 2 出于「骨架可独立启动」的考虑，把 status 校验做成 `ObjectProvider<UserStatusValidator>` 可选扩展点，
接口只有一个 `isActive(Long) → boolean`。阶段 5 把它**升为三态枚举**：

```java
enum AccountState { ACTIVE, DISABLED, UNKNOWN }
AccountState check(Long userId);
```

**为什么不继续用 boolean**：`false` 一个值要同时承载两种语义，但它们的正确行为不同且用户可见：

| 场景 | HTTP | code | 文案 | 用户该做什么 |
|---|---|---|---|---|
| `status = 0`（被管理员禁用） | 401 | **1003** | 账号已被禁用，请联系管理员 | 找管理员解封 |
| 记录不存在 / 已逻辑删除 | 401 | **1006** | 登录状态已失效，请重新登录 | 清 token 重新登录 |

若都归为「禁用」，一个已被注销的账号持有者会被告知「联系管理员」——而管理员那里根本没有这个账号，用户被卡死。
契约 §0 只为「账号禁用」分配了 1003、为「token 无效」分配了 401 的 HTTP 语义却未给码值，故此处补 1006（契约增补项，见 §6）。

**这个缺陷是如何被发现的**：改造阶段 2 遗留测试时暴露——
那两个测试类原先直接 `jwtUtil.generateToken(999L, "USER")` 伪造一个**库里不存在的 userId** 来通过拦截器。
阶段 5 接入真实查库后，同一份 token 会被判为 `UNKNOWN` 并得到 401，测试立刻失败。
这恰好证明扩展点真的接上了（而不是「注入了一个永远返回 true 的假实现」）。

---

## 3. 并发正确性：唯一性不能只靠「先查后插」

`register` 与 `wxLogin` 的建号都是「先 `selectOne` 看有没有，没有就 `insert`」。
这个模式有天然竞态窗口：两个并发请求可以都通过检查、都执行插入。

**处理方式**：不引入乐观锁/分布式锁，而是让**数据库唯一索引**兜底，捕获 `DuplicateKeyException` 后转为同一业务语义：

| 入口 | 冲突来源 | 处理 |
|---|---|---|
| 注册 | `uk_username` | → 1001「用户名已被占用」（与预检查同一语义） |
| 微信建号 | `uk_openid` | 重查一次，拿到并发请求刚建好的账号，正常登录 |

**唯一索引冲突后重查仍失败**时（说明冲突来自其它唯一键或数据异常），**原样抛出**，由全局处理器记 9001——
绝不静默吞掉（红线：禁 catch 后什么都不做）。

单测里对这条分支专门做了**桩注入**：`selectOne` 第一次返回 null、第二次返回已存在用户，
`insert` 抛 `DuplicateKeyException`——无法通过 HTTP 稳定触发（需真实并发时序），只能在服务层用 mock 锁定。
另有反例用例：冲突后重查仍为 null → 断言 `DuplicateKeyException` 被上抛。

---

## 4. 安全审查（逐条对照 spec/logging-guidelines.md 与 quality-guidelines.md）

| 红线 | 落实方式 |
|---|---|
| 密码只存 BCrypt | `new BCryptPasswordEncoder(10)`；注册时 `encode`；登录时 `matches`。冒烟后 SQL 复查：`password_hash NOT LIKE '$2a$%'` 的行数 = **0** |
| 不记录密码/哈希 | `login` 失败只记 `username`；`AuthServiceImpl` 全类无 `password` 变量入日志（含 DEBUG 级） |
| 不记录 token | `JwtInterceptor` / `AuthServiceImpl` 日志中无 token 值 |
| 不记录微信 secret / code | `WxApiClient` 异常日志**绝不打印 uri**（query 里拼了 secret），只记异常类名；不记 `code` |
| 非 HTTP 入口也要安全 | 头像白名单在 Service 层用 `Constants.isValidAvatar`（`AVATARS` 是唯一事实源），不只依赖 DTO 正则 |
| 无越权写入 | `profile` / `updateProfile` 的 userId 取自 `UserContext`（token），**不接受请求参数**传入 |
| 无用户枚举 | 用户不存在与密码错误同返 1002；日志里也只有用户名 |
| 禁硬编码凭证 | `application.yml` 中为 `${WX_APPID:}` / `${WX_SECRET:}`；真实值仅在 gitignore 的 `application-local.yml` |
| 列表接口分页 | 本阶段无列表接口（N/A） |

**微信用户的数据层约束**：`username` 与 `password_hash` 均置 NULL。
MySQL 唯一索引允许多个 NULL，故多个微信用户不会因 NULL username 互相冲突；
同时这从数据层保证微信账号**无法**走密码登录路径（`login` 见到 `password_hash` 无内容即返 1002，且不 NPE）。

---

## 5. 实测验证清单（全部可重复执行）

### 5.1 单元/集成测试

```
mvn -o clean test   →  Tests run: 118, Failures: 0, Errors: 0, Skipped: 0   （连跑 3 次稳定）
```

| 测试类 | 数量 | 说明 |
|---|---|---|
| `client/WxApiClientTest` | 8 | 起**真实本地 HTTP 桩服务**（`com.sun.net.httpserver`），非 mock 方法——覆盖 URL 拼接、errcode 分支、非 JSON 响应、连接失败、缺凭证不发请求 |
| `service/AuthServiceImplTest` | 21 | 真实库 + 并发分支桩注入；含 profile 打卡天数去重、avatar 白名单全遍历、越权写入防护 |
| `controller/AuthControllerIntegrationTest` | 25 | MockMvc 真实 HTTP，覆盖 5 接口的外部形状 + §8.7 禁用即时失效 |
| `config/JwtInterceptorIntegrationTest`（改造） | 19 | 阶段 2 的 12 条 + 新增三态/`UserContext` 隔离断言 |
| `common/GlobalExceptionHandlerIntegrationTest`（改造） | 8 | 改为先建真实用户再签 token |

**为何用真实桩服务而非 mock 对象**：`WxApiClient` 的核心风险在跨层（参数有没有真的拼进 query、非 JSON 响应会不会炸成 500），
mock 掉方法就测不到了。桩服务让「appid 不匹配 → 40013 → 1004」这类分支真实可测。

### 5.2 真实环境闸口冒烟（MySQL 8.4.9 + 微信线上 API）

`java -jar target/vocab-backend-1.0.0.jar --spring.profiles.active=local` 起真实服务，脚本 `/c/tmp/stage5-smoke.sh`：

```
=== AUTH GATE RESULT: PASS=38 FAIL=0 ===
```

关键断言：

| 断言 | 实测 |
|---|---|
| 注册成功且**不返回** token | `data.token` 不存在 ✓ |
| 昵称缺省取 username | `nickname == username` ✓ |
| 重复注册 | 200 + 1001「用户名已被占用」 ✓ |
| 弱密码 / 短用户名 | 1005 ✓ |
| 登录返回可用 token | 182 字符，可被 `parseToken` 解回 userId/role ✓ |
| 密码错 / 用户不存在 | 均 1002（不枚举） ✓ |
| 无 token / 畸形 token | 401 + 1006 ✓ |
| `totalCheckInDays` 无记录 | 0（非 null） ✓ |
| `createTime` 格式 | `2026-10-16 18:11:07`（空格分隔） ✓ |
| avatar_9 / avatar_0 / 空更新 | 均 1005 ✓ |
| 微信无效 code（**真实调微信**） | 微信返 `errcode=40029` → 应用 1004 ✓ |
| 空 code | 1005 ✓ |

**§8.7 禁用即时生效（真实 SQL 操作 + 同一 token 复测）**：

```
BEFORE disable: HTTP=200 code=0 userId=12
DB status now = 0            ← 直接 UPDATE user SET status=0
AFTER  disable: HTTP=401 code=1003
RE-LOGIN while disabled: HTTP=200 code=1003
```

**UTF-8 逐字节校验**（防中文乱码，控制台编码不可信）：

```
响应原始字节: {"code":"1002","message":"\xe7\x94\xa8\xe6\x88\xb7...\xe8\xaf\xaf","data":null}
message utf8 hex = e794a8e688b7e5908de68896e5af86e7a081e99499e8afaf
expected        = e794a8e688b7e5908de68896e5af86e7a081e99499e8afaf
MATCH = True
```

### 5.3 红线比对

| 项 | 结果 |
|---|---|
| `anime_tracker` 表数 | 20（未变，未触碰） |
| `ai_vocab` 表数 | 7（未变） |
| 冒烟数据清理 | `user` 表回到 0 行 |
| `main` 下 `System.out` / `printStackTrace` / 硬编码凭证 | 均无 |
| `application-local.yml` 是否被 git 跟踪 | 否（`.gitignore:5` 命中） |
| 时间戳填充 | `create_time` / `update_time` 由 `MetaObjectHandler` 写入（阶段 3/G2 决策），实体带 `@TableField(fill=...)` |

---

## 6. 契约增补项（→ 需在 **G6 前**回写 `docs/接口详细设计.md`）

| # | 增补内容 | 缘由 |
|---|---|---|
| 1 | §0 错误码表补 **1006 TOKEN_INVALID**「登录状态已失效，请重新登录」 | 契约 §0 把 401 统一描述为「token 缺失/过期/账号禁用」，却只为「禁用」给了 1003。token 无效必须与禁用区分（用户动作不同） |
| 2 | §0 错误码表补 **1007 FORBIDDEN**「无权访问该资源」 | 契约 §0 只说 403 = 非管理员访问管理端，未给码值；补码后 403 响应体也符合 `Result` 结构 |
| 3 | §0 补注「HTTP 4xx 保留给路由/协议层错误」+ 9002/9003/9004 | 404/405/415 不能被兜底处理器吞成 200，否则联调时无法区分「路径写错」与「服务故障」 |
| 4 | §0 补注 **JSR-310 全局序列化格式**（`YYYY-MM-DD HH:mm:ss` / `YYYY-MM-DD`） | `spring.jackson.date-format` 对 `java.time` 无效，必须由 `JacksonConfig` 显式配置；契约应写明该格式是后端保证而非前端兜底 |
| 5 | `wx.code2session-url` 参数化为配置项 | 便于测试与灰度；同时避免硬编码第三方 URL |

---

## 7. 遗留与后续

- **未覆盖**：真实小程序 `wx.login()` 换取的 code 未测（需真机/开发者工具）。**但**真实微信 API 已打通：
  无效 code 得到 `errcode=40029`，证明 appid/secret 有效、网络可达、错误映射正确。
  唯一未验的是「有效 code → 得到 openid → 自动建号」这一步，它在单测中由桩服务覆盖（`openid-new-*` 用例）。
- **性能注记**：每请求一次 `selectById`。一期用户规模小可接受；若 QPS 上升，可在 `UserStatusValidatorImpl` 内部加短 TTL 缓存（调用方无需改动）。
- **无分页缺口**：本阶段接口均非列表，无需分页。
- **wx 昵称唯一性**：后缀 4 位（36^4 ≈ 168 万组合），按 nickname 不设唯一约束（契约未要求），仅用于展示区分。
