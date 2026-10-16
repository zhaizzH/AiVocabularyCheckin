# 阶段 10（管理模块）实施与闸口记录

> 任务：`.trellis/tasks/10-16-backend-api`（父任务 `10-16-aivocab-fullstack`）
> 阶段：10. 管理模块（G6 契约已冻结 → 编码 → G7 前的真机验证）
> 前置：G6 已过（`29e8e5f`，仅回写 `docs/接口详细设计.md` V1.1 + `docs/S3-...-正文.md` V1.3）
> 结论：**阶段 10 源码 + 测试完成；`46 PASS / 0 FAIL` 真机冒烟通过；全量 326 测试全绿；红线未触碰。**

---

## 1. 范围与契约对齐

契约已冻结于《接口详细设计》**V1.1 §8**，本阶段共实现 10 个管理端接口（7 个契约原稿 + 3 个补齐的 8.8/8.9/8.10），**编码严格照 V1.1，未再改契约**。

| 契约节 | 方法/路径 | 实现位置 |
|---|---|---|
| §8.1 | `POST /api/admin/books/import`（multipart `file`/`name`/`category`） | `AdminBookController#importBook` → `WordBookService.importBook` |
| §8.2 | `GET /api/admin/words`（`bookId`/`keyword`/`page`/`size`） | `AdminWordController#list` → `AdminService#listWords` |
| §8.3 | `POST /api/admin/words` | `AdminWordController#create` → `AdminService#createWord` |
| §8.4 | `PUT /api/admin/words/{wordId}` | `AdminWordController#update` → `AdminService#updateWord` |
| §8.5 | `DELETE /api/admin/words/{wordId}` | `AdminWordController#delete` → `AdminService#deleteWord` |
| §8.6 | `GET /api/admin/users`（`keyword`/`page`/`size`） | `AdminUserController#list` → `AdminService#listUsers` |
| §8.7 | `PUT /api/admin/users/{userId}/status` | `AdminUserController#updateStatus` → `AdminService#updateUserStatus` |
| §8.8 | `GET /api/admin/books`（**裸数组**，含 `createTime`） | `AdminBookController#list` → `WordBookService.adminListBooks` |
| §8.9 | `DELETE /api/admin/books/{bookId}` | `AdminBookController#delete` → `WordBookService.deleteBook` |
| §8.10 | `GET /api/admin/monitor/ai-stats` | `AdminMonitorController#aiStats` → `AdminService#aiStats` |

**鉴权**：全部 `/api/admin/**` 由阶段 5 已落位的 `JwtInterceptor`（`/api/admin/` + 非 `ADMIN` → HTTP 403 + 1007）统一拦截；**本阶段未改动拦截器**。

---

## 2. 产出文件

### 新增（源码）

| 文件 | 契约 | 说明 |
|---|---|---|
| `dto/request/WordUpsertRequest.java` | §8.3/§8.4 | 新增/修改共用（契约 §8.4「字段同 §8.3」）；只加 `@Size`，不加字段级必填——同一字段在两场景必填性相反，Bean Validation 表达不了，交 Service 判 1005 |
| `dto/request/UserStatusRequest.java` | §8.7 | `Integer status` + `@NotNull`。**刻意用 `Integer`**：若用 `int`，漏传时默认 0 会被当成「禁用」静默生效 |
| `dto/response/AdminUserResponse.java` | §8.6 | 独立 DTO，**无 `passwordHash` / `deleted`** |
| `dto/response/AdminBookResponse.java` | §8.8 | 独立于 `BookResponse`（管理端要 `createTime`，不要 `selected`/`learnedCount`） |
| `dto/response/AiStatsResponse.java` | §8.10 | 顶层聚合 |
| `dto/response/AiFeatureStat.java` | §8.10 | `features[]` 元素 |
| `dto/response/DegradeEventView.java` | §8.10 | `recentEvents[]` 元素；**不复用 `AiMetrics.DegradeEvent`**（内部对象不得越过边界） |
| `dto/response/WordDeleteResponse.java` | §8.5 | `{wordId, deleted:true}`（独立成类以保字段名合契约，不用泛型） |
| `dto/response/BookDeleteResponse.java` | §8.9 | `{bookId, deleted:true}` |
| `dto/response/UserStatusResponse.java` | §8.7 | `{userId, status}` |
| `dto/response/UserCheckInCount.java` | §8.6 | **内部投影**，避用户列表 N+1 |
| `common/SuccessRate.java` | §8.10 | 成功率唯一真相源 |
| `service/AdminService.java` | — | 接口（词书接口不在本层：复用 `WordBookService`） |
| `service/impl/AdminServiceImpl.java` | — | 单词 CRUD / 用户列表 / 启禁 / AI 监控 |
| `controller/admin/AdminWordController.java` | §8.2–8.5 | `/api/admin/words` |
| `controller/admin/AdminUserController.java` | §8.6/§8.7 | `/api/admin/users`；`operatorId` 取 `UserContext.getUserId()`，**不接受请求参数**（防伪造操作者） |
| `controller/admin/AdminBookController.java` | §8.1/§8.8/§8.9 | `/api/admin/books` |
| `controller/admin/AdminMonitorController.java` | §8.10 | `/api/admin/monitor/ai-stats` |

### 修改（源码）

- `service/WordBookService.java` + `service/impl/WordBookServiceImpl.java`：`adminListBooks()` 返回类型 `List<BookResponse>` → **`List<AdminBookResponse>`**。
- `mapper/StatsMapper.java` + `resources/mapper/StatsMapper.xml`：新增 `countCheckInDaysByUsers(userIds)` → 一次 `GROUP BY user_id` 取多用户打卡天数（与页大小无关，恒定 1 次查询）。

### 新增（测试 / 脚本 / 文档）

- `src/test/java/com/vocab/controller/admin/AdminControllerIntegrationTest.java`（**31** 用例）
- `src/test/java/com/vocab/service/AdminServiceImplTest.java`（**13** 用例）
- `src/test/java/com/vocab/common/SuccessRateTest.java`（**5** 用例）
- `backend/scripts/stage10-admin-smoke.py`（**46** 用例 A0–H1，真 jar + 真 MySQL + 真 HTTP）
- `src/test/java/com/vocab/config/JwtInterceptorIntegrationTest.java`（连带修 2 处断言）
- 本文件 `research/10-g6-stage10-admin-review.md`

---

## 3. 修掉的真问题

### 3.1 参数语义混淆：`createWord` 把「缺 bookId」与「词书不存在」揉成一条

**症状（编码时自查发现）**：`createWord` 原本第一句就 `requireBook(request.getBookId())`。当 `bookId == null` 时，要么落进 2001「词书不存在」，要么在拼接查询条件时先空指针——两条语义被混成一条，前端无法区分「**我少传了字段**」和「**词书已被删**」。

**修法**：新增前置判定
```java
if (request.getBookId() == null) {
    throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "bookId 不能为空"); // 1005
}
requireBook(request.getBookId()); // 2001
```
**回归防护**：`AdminServiceImplTest#createWordDistinguishesMissingAndNonexistentBook` 同时断言 1005 与 2001。

### 3.2 连带：管理端 Controller 落地让 `JwtInterceptorIntegrationTest` 两处断言失效

**症状**：此前用「`/api/admin/users` 返回 404」来间接证明「请求已通过鉴权拦截器、只是没有对应 Controller」。第 10 阶段建了真实 Controller 后，该路径返回 200，两处断言（`adminRoleAllowedOnAdminPath`、`disabledAdminAlsoRejected` L280）由绿转红。

**修法**：把断言从「等于某个具体状态」改为语义断言「**非 401/403 即说明已过拦截器**」，引入常量 `AUTH_PASSED=200`。这样以后每新增一个管理端接口都不会再误报。**注意**：这不是放宽测试——「403+1007」与「401」的断言仍原样保留，只是「放行」的判定不再绑定到「404」这一巧合。

### 3.3 `word_count` 原子自减必须手填 `update_time`

`adjustWordCount` 走 `setSql("word_count = word_count + (delta)")` + `update(null, wrapper)`；因 `entity == null`，MyBatis-Plus 的 `MetaObjectHandler` **不会**触发 UPDATE 填充，若不显式 `set(WordBook::getUpdateTime, LocalDateTime.now(clock))` 就会留下「行改了、`update_time` 没变」的脏数据。用注入的 `Clock` 而非 SQL `NOW()`，与「时间边界由 Java 传参」红线一致，且测试可固定时间。

### 3.4 成功率口径（抽出 `SuccessRate`，并补上退化分支的测试锁）

原实现把成功率算法内联在 `AdminServiceImpl` 私有方法里，缺测试。抽出 `common/SuccessRate#percent(success,total)` 并补 `SuccessRateTest`(5)，**锁死两条最容易被改坏的口径**：
- **分母含降级**：否则「成功率」永远是 100%（分母里只剩成功），健康指标失去信息量；
- **无调用返回 100 而非 0**：语义是「没有失败」，与 `MasteryRates#percent` 的「分母为 0 取 100」同一约定；否则新部署时管理端看板会显示 0%，看起来像服务全挂了。

### 3.5 测试自身 3 处错误（非实现缺陷）

| 现象 | 根因 | 修法 |
|---|---|---|
| `AdminServiceImplTest#deleteWordIsLogical` 期望 0 实得 1 | 夹具把 `word_count` 初值设 1，`createWord` 又 +1 至 2，删除后剩 1 | 初值改 0，并显式断言「新增后为 1」 |
| `assertEquals(long, int)` 编译不通过（歧义） | `getRegisteredUsers()` 返回 `int`，与 `long` 比对命中重载歧义 | 显式 `(long)` 强转 |
| `aiStats` 成功率断言随用例顺序漂移 | `AiMetrics` 是**单例内存计数器**、与 Spring 上下文共享；同上下文其它用例（如 AI 集成测试）已写入计数 | 断言改「前后快照差值」，消除隐式顺序依赖 |

---

## 4. 关键设计决策（阶段 10）

1. **词书接口复用，不重复造 Service**：`AdminBookController` 注入 `WordBookService`（`adminListBooks` / `importBook` / `deleteBook` 已于阶段 6 实现），只有 Word / User / Monitor 落在新的 `AdminService`。
2. **`AdminBookController` 的边界异常翻译**：`MultipartFile#getInputStream()` 抛的 `IOException` 属 Web 层入参搬运，不是业务规则；若不翻译会被全局兜底成 9001，与契约 §8.1「file 流不可读 → 2004」不符。故在 Controller 内 try-with-resources + 翻译为 `BizException(IMPORT_FILE_INVALID=2004)`。
3. **`UserStatusRequest` 用 `Integer`**：见 §2 表。
4. **响应 DTO 一律独立成类**（不复用 `BookResponse` / `AiMetrics.DegradeEvent`）：管理端字段集合与用户端/内部结构本就不同，复用会让「用户端加个字段」意外改到管理端契约。
5. **`UserCheckInCount` 投影避 N+1**：用户列表页正常 20 行，若逐行 `COUNT` 即 21 次查询；改为**一次** `GROUP BY user_id`。空页时短路不查（`IN ()` 语法错误）。XML 内显式写 `deleted = 0`（`@TableLogic` 对自定义 XML 不生效——阶段 4 已确认的通用坑）。
6. **`aiStats` 的 `registeredUsers`** 用 `userMapper.selectCount(null)`，`@TableLogic` 会自动附加 `deleted = 0`。

---

## 5. 闸口验证（G7 前的真机证据）

### 5.1 构建

```
mvn -o -q clean package -DskipTests
→ target/vocab-backend-1.0.0.jar  30,762,584 B
```
启动：`java -jar target/vocab-backend-1.0.0.jar --spring.profiles.active=local`
→ `Started VocabApplication in 8.861 seconds`，Tomcat 监听 8080（PID 12424）。

### 5.2 阶段 10 冒烟（真 jar + 真 MySQL 8.4.9 + 真 HTTP）

```
DBPASS=... python backend/scripts/stage10-admin-smoke.py
== 阶段 10 管理模块冒烟 http://127.0.0.1:8080（真 jar + 真 MySQL）==
...
== 汇总 46 PASS / 0 FAIL ==   (exit=0)
```

覆盖矩阵：

| 组 | 用例 | 覆盖点 |
|---|---|---|
| A0–A1 | 2 | 无 token → HTTP 401 + 1006；普通 USER → HTTP 403 + 1007 |
| B0–B3 | 4 | §8.8 裸数组；三书计数 7508/5651/9602 与 S3 一致；含 `createTime`、**无** `selected`/`learnedCount`；时间格式 `YYYY-MM-DD HH:mm:ss` |
| C0–C3 | 4 | §8.1 3 行含 1 坏行 → `success:2 / skipped:1`；`word_count` 校准为实际合法行数；非法 `category` → 1005；整份无合法行 → 2004 **且不留 0 词空壳词书** |
| D0–D11 | 12 | §8.2 分页结构 + `wordId` + keyword 模糊；§8.3 新增 `wordCount+1` / 词书不存在 2001 / 空白 word 1005 且计数不动；§8.4 只改传入字段 / 不存在 → **1005（无 2005）**；§8.5 `{wordId,deleted:true}` / **直查库断言行 `deleted=1`（逻辑删）** / 计数 −1 |
| E0–E10 | 11 | §8.6 分页 + 真实打卡 → `totalCheckInDays=1`（同日多流水算 1 天）/ 未打卡 0 / 不泄 `passwordHash`/`deleted`；§8.7 禁用后该用户立刻 401 + 1003 / 启用恢复 / 禁 ADMIN → 1005 / 缺 `status` → 1005 / 非法 `status` → 1005 / 目标不存在 → 1005 |
| F0–F6 | 7 | §8.10 `features` 固定三项顺序；`totalCalls` 含降级；**真实触发一次 AI 降级后 `degradedCount` +1**；`recentEvents` 最新在前 |
| G0–G3 | 4 | §8.9 逻辑删词书 + 连带逻辑删单词；不存在 → 2001 |
| H0–H1 | 2 | 回归：种子书单词分页 `total` 与 `wordCount` 恒等（未被测试污染） |

**自包含性**：仅创建/清理自己的测试用户（前缀 `g6adm_` / `g6usr_`）与测试词书（名 `G6冒烟`），运行结束按 username/name 精确物理清理。冒烟后直查库：`user` 无 `g6*` 残留、无 `G6*` 词书、`word` 回到 22761、`word_book` 回到 3 行。

### 5.3 回归（同一运行中的服务）

| 脚本 | 结果 |
|---|---|
| `g4-smoke.py` | 26 PASS / 0 FAIL |
| `g4-crossday.py` | 18 PASS / 0 FAIL |
| `g5-smoke.py` | 24 PASS / 0 FAIL |
| `stage9-stats-smoke.py` | 29 PASS / 0 FAIL |

### 5.4 全量单元/集成测试

```
mvn -o test
→ Tests run: 326, Failures: 0, Errors: 0, Skipped: 0
→ BUILD SUCCESS
```
（阶段 9 末 277 → +31 `AdminControllerIntegrationTest` +13 `AdminServiceImplTest` +5 `SuccessRateTest`）

### 5.5 服务日志与红线

- 服务启动至冒烟结束：`grep -cE " ERROR "` = **0**（WARN 5 条，均为预期的 AI 降级 WARN，属正常降级路径）。
- **红线 `anime_tracker`：20 张表，未变**。
- `ai_vocab` 抽查：`user` 26 / `word` 22761 / `word_book` 3 / `word_progress` 463 / `study_record` 523 / `mistake_entry` 20 / `user_word_book` 25（均含历次冒烟遗留的测试用户数据，非污染既有数据）。

### 5.6 规范红线自查

| 红线 | 结果 |
|---|---|
| Controller 不写业务逻辑 / 不返回实体 | ✅ 4 个 Controller 全部只做参数搬运 + 调用 Service，返回 `Result<DTO>` |
| 无 `System.out.println` / `printStackTrace` | ✅ grep 0 命中 |
| 无硬编码密钥/URL | ✅ grep 0 命中 |
| 列表接口必分页 | ✅ `listWords` / `listUsers` 走 MyBatis-Plus `Page` + `PageResult`（管理端也不例外） |
| 每请求 DTO `@Valid` | ✅ `@Valid @RequestBody WordUpsertRequest/UserStatusRequest` |
| 新接口走 `Result` 包装 | ✅ 全部 |
| 日志 `{}` 占位符 | ✅ |
| SQL 聚合不在 Java 内存里算 | ✅ `countCheckInDaysByUsers` 一次 `GROUP BY` |
| 时间边界由 Java 传参 | ✅ `LocalDateTime.now(clock)`，无 `NOW()`/`CURDATE()` |

---

## 6. 遗留 / 后续

- 阶段 11 收尾待办（**不在阶段 10 范围**）：
  - `application.yml` 生产默认 `com.vocab.mapper: DEBUG` 会打印 `password_hash` 的 BCrypt 哈希 → 生产默认改 `INFO`，`mapper` DEBUG 仅保留在本地 profile（阶段 8 G5 发现）。
  - S3 §8.2 登记 AI 供应商 DeepSeek。
  - `backend/README.md` 完善 + `scripts/smoke-test.sh` 一键脚本 + 逐条自查 `prd.md` 验收标准。
- 未处理的工作区残留（**非本任务引入**）：23 个 `docs/logo/*.png` 删除、`docs/小程序注册信息-名称简称介绍.md` 删除。
