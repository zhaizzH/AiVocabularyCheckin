# 实施期发现的契约缺口（阶段 2 工程骨架）

> 本文件是**待回写清单**。按闸口 G6（「先文档后代码，不可颠倒」），
> 这些缺口必须在阶段 10（管理模块改契约文档）时一并回写到
> `docs/接口详细设计.md`，并同步 S3。
>
> 阶段 2 的实施已经**按这里描述的行为写死代码**（已由 37 个单测锁定），
> 因此回写文档是「补齐描述」而非「改变行为」——风险可控。

## 缺口 1：401 语义被合并，缺少「token 无效/过期」码值

- **契约原文**（§0 鉴权段）：`401 = token 缺失/过期/账号禁用`。
- **问题**：三件事共用一个 HTTP 状态码可以，但错误码只分配了 `1003 账号已被禁用`。
  token 过期/缺失/被篡改时**无码值可用**，前端无法区分「该重新登录」与「账号被封」，
  而这两者的 UI 引导完全不同（前者跳登录页，后者提示联系管理员）。
- **决策**：补 `1006 TOKEN_INVALID = "登录状态已失效，请重新登录"`；
  `1003` 仅保留给「账号禁用」。
- **影响面**：`JwtInterceptor` 所有 401 分支（缺头、格式错、解析失败、无 userId）

## 缺口 2：403 有语义无码值，响应体无法构成 Result

- **契约原文**（§0）：`403 = 非管理员访问 /api/admin/**`。
- **问题**：契约要求所有响应体都是 `{code,message,data}` 结构，但 403 没有码值，
  实现时只能编造一个，或返回空体（破坏前端统一解包逻辑）。
- **决策**：补 `1007 FORBIDDEN = "无权访问该资源"`。

## 缺口 3：路由层错误（404/405/415）误落入「业务失败 HTTP 200」

- **契约原文**（§0）：`业务失败 HTTP 状态码仍为 200，错误通过 code 表达`。
- **问题**：这条约束的语义是「业务逻辑失败」，但实现时若把全局兜底异常处理器
  写成 `@ExceptionHandler(Exception.class)` → 200 + 9001，
  则「路径写错」「方法写错」会被吞成 200 + 系统繁忙。
  实测确认过：阶段 2 首次实现即出现该现象（`POST /api/client/auth/register`
  返回 200 + `9001` 而非 404），会让联调时把「接口没实现」误判为「服务故障」，
  也掩盖真实 bug。**这是一个真实踩到并修掉的坑。**
- **决策**：
  - `NoResourceFoundException` → **HTTP 404** + `9002 请求的接口不存在`
  - `HttpRequestMethodNotSupportedException` → **HTTP 405** + `9003 请求方法不支持`
  - `HttpMediaTypeNotSupportedException` → **HTTP 415** + `9004 不支持的 Content-Type`
  - `HttpMessageNotReadableException`（请求体非法 JSON）→ **HTTP 200 + 1005**
    （这条**属于**业务失败范畴，保持 200）
- **建议回写**：在契约 §0 补一句边界说明——
  「HTTP 4xx（404/405/415）保留给路由层错误；除鉴权外，控制器可达的错误一律 200 + code」。

## 缺口 4：CORS 白名单未参数化

- **问题**：管理端 dev server 端口写死在代码里，部署时需改代码。
- **决策**：新增配置项 `cors.allowed-origins`（默认 `http://localhost:5173,http://127.0.0.1:5173`），
  可用环境变量 `CORS_ALLOWED_ORIGINS` 覆盖。属配置项而非接口契约，回写 S3 部署章节即可。

## 缺口 5：`mybatis-plus-jsqlparser` 必需依赖（非契约问题，属实施坑）

- MyBatis-Plus **3.5.9 起**，`PaginationInnerInterceptor` 等 JSQLParser 系拦截器
  被从 `mybatis-plus-extension` 拆分到独立 artifact。
- 不显式引入则**编译失败**（`找不到符号 PaginationInnerInterceptor`）。
- 已加入 `pom.xml`。若将来升级 MyBatis-Plus，需注意该模块版本必须与 starter 严格一致。

## 缺口 6：`UserStatusValidator` 扩展点（骨架期占位）

- 契约要求「管理员禁用用户后，该用户下次请求立即失败（401 + 1003）」，
  这需要**每个请求查库**校验 `user.status`，不能只信任 token。
- 阶段 2 尚无 User 表与 Mapper，故抽象出 `UserStatusValidator` 接口，
  由 `ObjectProvider` 可选注入：实现缺失时跳过校验（仅骨架期），
  阶段 5 由 `AuthService` 提供真实实现后自动生效。
- **回写提醒**：契约 §8.7 应明确「禁用即时生效」的校验时机（每请求查库），
  以及随之而来的性能取舍（一期 QPS 低，正确性优先）。
