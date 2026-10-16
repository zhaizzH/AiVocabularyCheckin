# G4 闸口记录：学习/复习/错题闭环（真实 MySQL + 真实 HTTP）

对应阶段：`implement.md` 阶段 7。
闸口定义：核心闭环必须能在**打包后的 jar + 真实 MySQL + 真实 HTTP** 上跑通，
而不只是 H2 + MockMvc 的进程内测试。

## 1. 为什么 G4 不能只靠 JUnit

阶段 7 的 231 个测试全部用 H2 内存库 + MockMvc（不起 HTTP、不连 MySQL）。
这能证明算法与契约字段正确，但**证明不了**下面三件事：

1. 打包后的 jar 能否真的启动并对外服务（依赖是否漏打、配置是否可解析）；
2. 日期边界在 MySQL 8.4 上的行为是否与 H2 一致
   （H2 不支持 `CURDATE()`/`DATE_SUB()`，阶段 4 起就刻意把时间边界交给 Java 传参，
   但「交给 Java 传参」这个决定本身在 MySQL 上是否等价，必须在 MySQL 上验）；
3. 唯一索引在真实 InnoDB 上的幂等行为（并发重复打卡/重复选词书）。

因此 G4 必须走真实链路。

## 2. 做了什么

### 2.1 打包

```
mvn -o -q clean package -DskipTests
→ target/vocab-backend-1.0.0.jar  30,702,336 B
```

> 第一次 `clean` 失败：`Failed to delete ... jar`。原因是一个**阶段 6 遗留的服务进程
> （PID 13500）仍持有该 jar**。杀掉后正常。这不是代码问题，但值得记一笔——
> 后续每次 G 闸口起服务前，先确认没有旧进程占端口/占文件。

### 2.2 冒烟一：`scripts/g4-smoke.py`（26 项）

以真实 HTTP 打 8080 端口，覆盖 §2/§3/§4/§5 的正常路径与错误路径：

| 断言 | 结果 |
| --- | --- |
| 无 token 访问受保护接口 → HTTP **401** + `1006` | PASS |
| 账号密码注册 / 登录拿 token | PASS |
| §3.1 未选词书 → `total=0`、`words=[]`、HTTP 200 | PASS |
| §2.1 词书列表 `data` 是**裸数组**（非 `{list:…}`）、含 3 本种子词书 | PASS |
| §2.3 选词书成功 / 重复选 → `2003` | PASS |
| §3.1 计划 `total=20`、`finished=0`、`checkedIn=false` | PASS |
| §3.1 词项字段齐全（`wordId/bookId/word/phonetic/translation/example/isReview/learned`） | PASS |
| §3.3 计划未完成打卡 → `3003`（**HTTP 200**） | PASS |
| §3.2 20 词上报，`addedToMistakes` 仅在 `known=false` 时为 true | PASS |
| §3.2 重复上报幂等 | PASS |
| **§3.1 计划窗口不漂移：上报完 20 词后 `total` 仍 20、`finished=20`** | PASS |
| §3.3 打卡成功 `streakDays=1/todayLearned=20/todayMistakes=1` | PASS |
| §3.3 重复打卡 → `3001` | PASS |
| §3.4 `streakDays=1/totalLearned=20/lastCheckInDate` 非空 | PASS |
| §5.1 错题列表 `{list,total,page,size}`、`total=1` | PASS |
| **§5.1 `mastered` 是 JSON 布尔（`False/True`），`source=STUDY`、`wrongCount=1`** | PASS |
| §5.1 `mastered=7` → `1005` | PASS |
| §5.2 标记已掌握 / 重复标记 → `4002` | PASS |
| §5.2 不存在的错题 → `4001` | PASS |
| §5.3 移除错题 / 再次移除 → `4001` | PASS |
| §4.1 刚学的词次日才到期 → 今日待复习 `total=0` | PASS |
| §4.2 未学习词提交复习 → `1005` | PASS |

补充（脚本外单独跑）：未选词书打卡 → `3002`（带体/不带体两种）；
新用户错题列表 `total=0`；越权标记/删除他人错题 id → `4001`。

**26 PASS / 0 FAIL。**

### 2.3 冒烟二：`scripts/g4-crossday.py`（18 项）—— 跨天

服务端日期无法在冒烟里拨动，故用「改库」构造「昨天」的事实。
自包含：每轮新建用户，先走完第一天，再把首日数据整体提前一天。

关键点：**`study_record.study_date` 与 `check_in_date` 在真实数据里恒等**
（打卡时同时写为当天），故必须**成对**回拨。第一版脚本只回拨 `check_in_date`，
造出了 `study_date=今天 + check_in_date=昨天` 这种现实中不可能出现的行，
从而让 `lastCheckInDate` 断言假失败。这是**夹具错误，不是产品缺陷**——
真实用户不可能产生这种行。修正为成对回拨后全绿。

| 断言 | 结果 |
| --- | --- |
| D1 计划 20 新词 / 学完打卡 `streak=1` | PASS |
| 回拨 `study_record` 20 行、`word_progress` 20 行 | PASS |
| D2 `checkedIn=false`（昨天打卡不算今天） | PASS |
| **D2 昨天学的 20 词今天作为复习词（`isReview=true`），overlap=20** | PASS |
| **D2 今天新词是下一批 20 个，与昨天 0 重叠** | PASS |
| D2 计划 `total=40`（20 复习 + 20 新词） | PASS |
| D2 未完成打卡 → `3003` | PASS |
| **D2 完成后 `finished==total==40`（计划未坍塌）** | PASS |
| **D2 跨天打卡 `streak=2`（昨天打过 → +1）** | PASS |
| D2 `totalLearned=40`、`lastCheckInDate=今天` | PASS |
| §4.1 到期词进今日待复习 `total=20`，字段齐全 | PASS |
| **§4.2 答对：`stage 1→2`、`next_review_date` 距今天 **3** 天（直接查库确认）** | PASS |
| **§4.2 答错：`stage` 退回 1、`next`=今天+1、错题本 +1（直接查库确认）** | PASS |
| §4.2 复习过的词不再到期（`total 19`） | PASS |
| **§4.2 `stage 3→4`、`next`=今天+15（间隔表最后一跳）** | PASS |
| **§4.2 已在 `stage=4` 再答对仍是 4（封顶）** | PASS |

**18 PASS / 0 FAIL。**

这三条是本次 G4 最有价值的证据，因为它们只在**真实 MySQL** 上成立：
`next_review_date` 与 `CURDATE()` 的差值由 MySQL 计算，Java 传参的 `since/today`
与 MySQL 的日期语义是否一致，这里得到了直接验证。

### 2.4 数据完整性抽查（pymysql 直连）

| 检查 | 结果 |
| --- | --- |
| **红线 `anime_tracker` 表数 = 20（未变）** | OK |
| `ai_vocab` 表数 = 7 | OK |
| `word=22761`、`word_book=3` | OK |
| 5 张表时间列 `create_time/update_time` 为 NULL 的行数 = 0 | OK |
| `learned=1 && next_review_date IS NULL` = 0 | OK |
| `mistake_entry` 按 `(user_id,word_id)` 重复组 = 0 | OK |
| `study_record` 按 `(user_id,word_id,study_date,study_type)` 重复组 = 0 | OK |
| `user_word_book` 按 `(user_id,book_id)` 重复组 = 0 | OK |
| `stage` 越界（不在 `[1,4]`）= 0 | OK |

最后四项说明阶段 3 的加固唯一索引 + 阶段 7 的写路径确实没有产生脏数据。

### 2.5 服务端日志

整个 G4 期间 grep `ERROR` = **0 条**。
`WARN` 仅来自微信 `code2Session`（冒烟走的是账号密码路径，未触发）。
业务异常全部以 `DEBUG` 记录（`GlobalExceptionHandler` 有意为之：
业务失败是预期路径，不该刷 ERROR）。

## 3. 本次 G4 发现并修复的缺陷

**无新增缺陷。** 阶段 7 的两处真缺陷（A 计划实时推导、B 候选集后滑）
在写测试阶段已被捕获并修复，见 `06-g4-stage7-study-loop-review.md`。

G4 的价值在于**确认修复在真实环境依然成立**：
- 缺陷 A 的回归验证 = §3.1「计划窗口不漂移」+ D2「完成后 finished==total==40」；
- 缺陷 B 的回归验证 = D2「今天新词是下一批、与昨天 0 重叠」。

## 4. 全量回归

```
mvn -o test → Tests run: 231, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS
```

15 个测试类：`WxApiClientTest`8 / `GlobalExceptionHandlerIntegrationTest`8 /
`ResultAndErrorCodeTest`11 / `JwtInterceptorIntegrationTest`19 / `JwtUtilTest`7 /
`AuthControllerIntegrationTest`25 / `BookControllerIntegrationTest`14 /
`StudyFlowControllerIntegrationTest`17 / `EntityMappingIntegrationTest`19 /
`AuthServiceImplTest`21 / `MistakeServiceImplTest`23 / `ReviewScheduleTest`8 /
`ReviewServiceImplTest`16 / `StudyServiceImplTest`24 / `WordImportServiceImplTest`11。

## 5. 结论

G4 **通过**。阶段 7 的学习/复习/错题闭环在真实 MySQL + 真实 HTTP 上端到端可用，
跨天连续打卡、间隔复习 stage 转移、计划窗口稳定、错题生命周期均符合契约。

两个冒烟脚本已入库（`backend/scripts/g4-smoke.py`、`g4-crossday.py`），
后续阶段回归可直接复用；它们只写 `g4_`/`g4cd_`/`g4n_` 前缀的测试用户，
不触碰既有数据，更不涉及红线 `anime_tracker`。

## 6. 待回写契约（累积，G6 统一处理）

在 `06-g4-stage7-study-loop-review.md` 的 4 条基础上，G4 又确认/补充：

5. **§3.1 `total` 语义** = 「今日任务集合大小」（含今天已上报的词），
   不是「还剩多少没做」。`finished` 单调递增，完成后 `finished==total`。
6. **§3.1 `learned` 口径** = 「今天是否已上报过该词」，与 `word_progress` 无关。
7. **§3.1 新词「未学过」定义** = 不存在 `create_time < 今日 00:00 且 learned=1` 的进度行；
   **判据必须是 `create_time` 而非 `update_time`**（后者每次复习被刷新）。
8. **§2.1 词书列表 `data` 是裸数组**，不是 `{list:[…]}`；
   元素用 `bookId`，§2.2 详情的分页在 `data.words.list`、元素用 `wordId`。
9. **§5.1 `mastered`**：响应是 JSON 布尔（`false/true`），请求 query 参数是 `0/1`。
10. **§4.2 `review/record` 请求体不带 `bookId`**（进度由 `uk_word_progress_user_word` 定位）。
11. **§4.1 `review/today` 响应不带分页字段**（契约即 `{total,words}`）。
12. **`study_record.study_date` 与 `check_in_date` 语义上恒等**（同一次打卡同时写入）。
13. **无 token / token 无效 → HTTP 401 + `1006`**（不是 1001）。
