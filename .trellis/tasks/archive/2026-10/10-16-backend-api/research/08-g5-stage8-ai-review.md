# G5 闸口记录：AI 中转与降级（阶段 8）

- 日期：2026-10-16
- 提交：`a097e6e`（阶段 8 实现）
- 闸口定义（implement.md）：**降级路径在 ai-service 未启动时全绿**（一等约束）
- 结论：**通过**。真实 MySQL + 真实 HTTP 端到端验证，24/24 冒烟项全绿，250 个单测全绿，服务端 ERROR=0。

---

## 1. 交付物

### 源码（新增 15 + 修改 3）

| 文件 | 作用 |
|---|---|
| `ai/AiMetrics.java` | 内存埋点（design.md §6.3）：按功能分组 `AtomicLong` 计数器 + 有界降级事件队列 |
| `ai/AiCallException.java` | 内部异常，带 `isTimeout()`；**不直接暴露前端** |
| `ai/AiServiceClient.java` | ai-service HTTP 客户端（契约 §9），三接口超时 15/30/60s |
| `ai/dto/Ai*Call*.java`（6） | 内部调用契约 DTO |
| `service/AiService.java` | 接口 + 三个用例降级差异的契约注释 |
| `service/impl/AiServiceImpl.java` | 编排 + 降级矩阵（契约 §6） |
| `controller/AiController.java` | `/api/client/ai/{example,explain,report}`（薄层，userId 取 `UserContext`） |
| `dto/request/{AiExampleRequest,AiExplainRequest}.java` | 请求体（`@NotNull`） |
| `dto/response/{AiExampleResponse,AiExplainResponse,AiReportContent,AiReportResponse}.java` | 响应体 |
| `common/Constants.java`（改） | `REPORT_RECENT_DAYS=14`、`REPORT_TOP_MISTAKES=10` |
| `mapper/StatsMapper.java` + `resources/mapper/StatsMapper.xml`（改） | `countCheckInDays` / `countCheckInDaysSince(since)` / `selectTopMistakeWords(limit)` |

### 测试（新增 2 类 + 2 工具，19 个用例）

| 文件 | 用例数 | 覆盖 |
|---|---|---|
| `service/AiServiceImplTest.java` | 13 | 降级矩阵三条路径 + 越权 + 埋点 |
| `controller/AiControllerIntegrationTest.java` | 6 | HTTP 契约形状 + 401/1005 |
| `testsupport/SilentHttpServer.java` | — | 真实读超时对端（接受连接但永不响应） |
| `testsupport/StubAiServer.java` | — | 真实 HTTP 假 ai-service（校验字段名） |

---

## 2. 降级矩阵实测结果

### 2.1 ai-service 未启动（连接被拒）

| 接口 | HTTP | code | 结果 |
|---|---|---|---|
| `POST /api/client/ai/example` | 200 | `"0"` | `degraded:true`，`example`=词库例句，`mnemonic:""` |
| `POST /api/client/ai/explain` | 200 | `"5002"` | 无 `data` |
| `POST /api/client/ai/report`（天数≥3） | 200 | `"5002"` | 无 `data` |
| `POST /api/client/ai/report`（天数<3） | 200 | `"5003"` | 无 `data`，**不发起 HTTP** |

### 2.2 ai-service 挂起（真实读超时，哑服务器监听 8000 但永不响应）

| 接口 | code | 日志 |
|---|---|---|
| `example` | `"0"` + `degraded:true` | `WARN AI 降级 feature=example reason=TIMEOUT costMs=1349 result=返回库内例句` |
| `explain` | `"5001"` | `WARN AI 降级 feature=explain reason=TIMEOUT costMs=1209 result=抛 5001` |

**这条路径是纯 Mock 测试覆盖不到的**，也是本次闸口真正发现缺陷的地方（见 §3）。

---

## 3. 本阶段发现的真实缺陷（1 个，测试先失败暴露）

### 缺陷：真实读超时被误判为「服务异常」5002，而非「超时」5001

- **现象**：`AiServiceImplTest.explainThrows5001OnRealTimeout` 失败——
  哑服务器造成真实读超时，断言应得 `5001`，实得 `5002`。
- **根因**：`AiServiceClient.isTimeout()` 只判 `java.net.SocketTimeoutException`。
  实测异常链（临时探针打印）：

  ```
  ResourceAccessException : I/O error on POST request for "...": Request cancelled
    └─ java.net.http.HttpTimeoutException : Request cancelled     <- 读超时
  ```

  Spring Boot 3.5 默认 `JdkClientHttpRequestFactory`（`java.net.http.HttpClient`）
  抛的是 `java.net.http.HttpTimeoutException`，而 `SimpleClientHttpRequestFactory`
  （`HttpURLConnection`）抛 `SocketTimeoutException`。**二者都是 `IOException`
  子类但互不继承（兄弟关系）**，故只判后者在**生产默认配置下**会漏判所有真实超时。
  连接被拒同样是 `ResourceAccessException`，但其 cause 是 `ConnectException`——
  说明「必须逐层下钻区分」这一思路是对的，只是漏了异常类型。
- **为什么单测此前没发现**：若用 Mock 伪造 `ResourceAccessException`，永远碰不到
  真实客户端抛的具体异常类型。这正是 G5 坚持用真实对端的价值。
- **修复**：`isTimeout()` 同时判 `SocketTimeoutException` 与
  `java.net.http.HttpTimeoutException`，并在方法 Javadoc 中写明原因——
  这是超时判定的唯一真相源，不应随底层实现更换而漂移。
- **回归防护**：`explainThrows5001OnRealTimeout` 与
  `explainThrows5002WhenServiceDown` 两个用例把「超时 vs 服务异常」锁死。

---

## 4. 测试用例自身错误（3 个，非实现缺陷）

| 用例 | 错误 | 修正 |
|---|---|---|
| `@DynamicPropertySource` + `@BeforeEach` 改静态字段 | 属性只在上下文**首次创建时**解析一次，运行期改 static 字段**不生效**——4 个用例全部打到同一端口，超时用例假失败 | 改为按用例手工 `new AiServiceImpl(...)` + 注入指定对端的 `AiServiceClient`，每个用例指向自己的对端 |
| 两个新测试类漏 `@Transactional` | 用例间泄漏数据到共享 H2，连带 `EntityMappingIntegrationTest.allTablesSelectable` 假失败（期望空表 0，实得 6） | 补 `@Transactional`（项目约定） |
| `seedCheckInDays` 重复插 `word_progress` | `(user_id, word_id)` 唯一键冲突；且 `countCheckInDays` 只读 `study_record` | 改为只写 `study_record` |
| G5 冒烟脚本 `POST /api/client/books/select` | 契约实为 `POST /api/client/books/{bookId}/select`（路径参数，无请求体）→ 9003 | 改对路径 |
| G5 冒烟脚本打卡断言 `D1` | 只上报 1/20 个词就打卡 → 正确地返回 `3003`（计划未完成），脚本误判为失败 | 脚本先跑完计划再打卡，并新增 `D0` 断言 `finished==total` |

> 最后两条是**脚本对契约理解有误**，产品行为正确。§3 的 `bookId` 路径与
> §3.3 的 3003 均与本阶段契约一致，无需回写。

---

## 5. 回归与数据完整性

- `mvn -o test`：**250 tests, 0 failures, 0 errors**（阶段 7 末为 231，+19）。
- 阶段 7 的 `g4-smoke.py` 重跑：**26 PASS / 0 FAIL**——证明阶段 8 对
  `StatsMapper.xml` 的增补（新增 3 查询）未破坏既有聚合。
- 红线 `anime_tracker` 表数 = **20**（未变）。
- `ai_vocab` 表数 = 7；不变量：`learned=1 && next_review_date IS NULL` = 0；
  `stage` 越界 = 0。
- 服务端日志 **ERROR = 0**（业务异常一律 `DEBUG`/`INFO`，`GlobalExceptionHandler` 有意为之）。
- G5 测试用户仅 `g5_*` / `g5t_*` 前缀，未触碰既有数据。

---

## 6. 敏感信息核查

对三份服务日志（`g5-server.log` / `g5-server2.log` / `g5-regress.log`）逐一 grep：

| 敏感值 | 出现次数 |
|---|---|
| AppSecret `0245f661…` | 0 |
| MySQL 密码 `<REDACTED_DB_PASSWORD>` | 0 |
| AppID `wxc99798ce418b7fce` | 0 |
| `jwt.secret` 明文 | 0 |
| 用户明文密码 | 0 |

**发现一处待办（非本阶段引入）**：`application.yml` 生产默认
`com.vocab.mapper: DEBUG`，MyBatis 参数日志会打印 `password_hash` 的 **BCrypt 哈希**
（实测日志可见 `$2a$10$…`）。明文未泄漏、哈希不可逆，但日志中留哈希不理想 →
记入阶段 11 收尾：生产默认应为 `INFO`，`mapper` 的 DEBUG 仅本地 profile 开启。

---

## 7. 待 G6 回写契约的增补项（阶段 8 新增）

1. **§6.2 超时判定说明**：`5001`（超时）与 `5002`（异常）的界线是
   「异常链中是否含超时异常」；连接被拒属 5002。底层客户端不同会抛
   不同超时异常类型（`SocketTimeoutException` / `HttpTimeoutException`），
   实现须两者都判。
2. **§6.3 `checkInRate` 窗口 = 14 天**（对齐「近两周」文案；契约未规定窗口，需明确）。
3. **§6.3 `masteryRate` 分母 = 错题本总条数**，无错题时取 100。
4. **§6.1 `example` 降级时若 AI 返回空 `example`，回退词库例句**（不是把空串给前端）。
5. **§6.2 `explain` 的 `wrongContext`** 由后端用「来源 + 累计错误次数」构造
   （项目不记录用户当时选错的选项，契约未规定上下文来源）。
6. **§9 内部接口调用方**：仅业务后端可调 ai-service；统计数字由后端算好放入请求体
   （ai-service 禁连 MySQL）。
7. **§6.1/§6.2 的 4001**：`explain` 的 `mistakeId` 不存在或不属于当前用户，**统一返回 4001**
   （避免通过 id 枚举探测他人错题）。
8. **§6.1 `wordId` 不存在 → 1005**；缺 `wordId`（`@Valid`）→ 1005。
9. **`ai-stats` 的字段口径**（阶段 10 实现时回写，此处先记）：
   `total`（含降级）/`success`/`degraded`/`timeout`/`error`/`avgCostMs`
   + 降级事件列表（`time/feature/costMs/reason/result`，容量上界 100）
   + 系统概览「注册用户数」。

---

## 8. 复现命令

```bash
# 1) 打包
cd backend && mvn -o -q clean package -DskipTests

# 2a) 【主场景】ai-service 未启动（127.0.0.1:8000 无监听）
java -jar target/vocab-backend-1.0.0.jar --spring.profiles.active=local
python backend/scripts/g5-smoke.py          # 期望 24 PASS / 0 FAIL

# 2b) 【补充场景】真实读超时：先起哑服务器占住 8000，再压短超时重启后端
python backend/scripts/silent-ai-server.py 8000 &
java -jar target/vocab-backend-1.0.0.jar --spring.profiles.active=local \
     --ai.service.example-timeout-ms=1200 --ai.service.explain-timeout-ms=1200
# 期望 example → code:"0"/degraded:true；explain → code:"5001"（不是 5002）
```

新增可复用脚本（已入库）：
- `backend/scripts/g5-smoke.py`（24 项，AI 降级契约）
- `backend/scripts/silent-ai-server.py`（哑 ai-service，制造真实读超时）
