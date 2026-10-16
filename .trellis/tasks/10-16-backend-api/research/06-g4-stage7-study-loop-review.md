# 阶段 7 复核：学习/复习/错题核心闭环（§3 / §4 / §5）

范围：`StudyService`、`ReviewService`、`MistakeService` 及其 DTO / Mapper / Controller，
外加时间注入基建（`ClockConfig` + `FixedClock` + `TestClockConfig`）与
`MybatisPlusConfig` 的填充改造。闸口 G4 关注「核心闭环端到端可跑通」。

## 1. 为什么先引入可注入的 Clock

「今天」是三个模块共用的核心变量：今日计划、今日上报去重、今日打卡、
待复习到期判断、今日错题数。原先若直接调用 `LocalDate.now()`，下面这些
必需用例只能靠「改系统时间」或「直接改库」来造，无法稳定复现：

- 昨天打卡 → 今天再打 → `streak` 应为 2；
- 断签两天后再打卡 → `streak` 应重置为 1；
- 昨天到期、今天待复习 / 明天才到期、今天不该出现；
- 今天新增错题数只统计当天。

做法：生产用 `ClockConfig` 注册 `Clock.systemDefaultZone()`，
所有相关 Service 构造函数注入 `java.time.Clock`，
测试用 `TestClockConfig`（`@Primary`）覆盖为 `FixedClock`（静态拨动日期）。
`ReviewSchedule` 的三个纯函数因此可脱离 Spring 直接测试。

### 1.1 连带修正：`MetaObjectHandler` 也必须用同一个 Clock

`create_time` / `update_time` 由 MyBatis-Plus 的 `MetaObjectHandler` 填充。
若它仍读 `LocalDateTime.now()`，而 Service 用 `Clock` 推「今天」，
则测试里把时钟拨到 2026-10-16 写入的行，其 `create_time` 会是真实系统时间，
与「今天」不一致，计划候选集判断就会出假阴性。

故 `MybatisPlusConfig#metaObjectHandler` 改为注入同一个 `Clock`。
这使「库里的时间戳」与「业务眼中的今天」永远同源，是时序类用例成立的前提。

## 2. 真缺陷（先写测试、测试失败、再修实现）

### 缺陷 A：计划实时推导导致「做完反而不能打卡」

**现象**：跨天端到端用例在「把今天计划里所有词都过完」之后调用
`POST /study/checkin`，得到 **3002「今日无学习计划」**，而不是打卡成功。

**根因**：`plan()` 原先完全由「当前待复习 + 当前新词候选」实时推导：

- 新词候选 `selectUnlearnedNewWordIds` 已排除 `learned=1` 的词；
- 待复习词要求 `next_review_date <= today`。

一个词被复习/学习上报后：`next_review_date` 被推到未来 → 不再是待复习词；
同时它 `learned=1` → 不再是新词候选。于是每完成一个词，
计划总量就少一个；全部完成后 `total` 变成 0。
`checkIn` 首先判断 `total == 0` → 抛 3002。
用户明明完成了全部任务，却无法打卡，**连续天数被无辜中断**。

**修复**：计划语义明确为「**今日任务集合**」，而不是「还剩多少没做」。
在复习词 + 新词两段之后，追加第三段：把「今天已经上报过的词」并回计划
（`todayReportedWordTypes`）。用 `LinkedHashMap` 去重并保持稳定顺序。
于是 `total` 恒定、`finished` 单调递增，做完即可打卡。

副作用是好的：昨天学过、今天没到期的词仍不会出现（`create_time` 是历史日期），
今天学过的词则稳定留在计划里并被标记 `learned=true`。

### 缺陷 B：计划候选集后滑（`NOT IN` 读法）

**现象**：`planSnapshotIsStableWhileReporting` 用 25 个词、逐词上报并每步重取计划，
断言 `total` 恒定。若按契约字面
`id NOT IN (SELECT word_id FROM word_progress WHERE learned=1)` 实现，
则每上报一个词，候选集立刻补进一个新词，`total` 恒为 20、`finished` 永远追不上。

**修复**：排除条件改为「**历史上已学过**」而非「有没有进度行」：

```sql
NOT EXISTS (
  SELECT 1 FROM word_progress p
  WHERE p.user_id = #{userId} AND p.word_id = w.id
    AND p.deleted = 0
    AND p.create_time < #{dayStart}   -- 昨天 00:00 之前
    AND p.learned = 1
)
```

判据必须是 **`create_time` 而不是 `update_time`**：
`update_time` 在每次复习后都会被刷新，用它会让「今天刚复习过的词」
被误判为「今天才学的新词」而重新入选，或反过来把历史学过的词重新当新词。

## 3. 测试发现的其它问题（测试自身写错，非实现缺陷）

- `mastered` 在响应里是**布尔**（契约 §5.1 `"mastered": false`），
  而在 query 参数里是 `0/1`。测试最初断言 `0` → 改为 `false`，
  并额外用 `JsonNode.isBoolean()` 锁死类型，防止将来有人「顺手」改成数字。
- 无 token 访问受保护接口返回 **401 + 1006**（token 无效），
  不是 1001（参数校验失败）。测试最初写错，已按阶段 5 的三态设计更正。
- 夹具造「历史上学过」的进度时，必须显式回填 `create_time` 为过去日期；
  否则 `strictInsertFill` 会填成今天，被测逻辑会把它当成「今天选中的新词」。
  这一坑本身印证了 §1.1 的必要性。

## 4. 覆盖情况

| 层 | 用例数 | 覆盖重点 |
| --- | --- | --- |
| `ReviewScheduleTest` | 8 | 间隔表 1/3/7/15、stage 封顶 4、越界夹取、跨月边界 |
| `StudyServiceImplTest` | 24 | 计划 20 词上限/多词书聚合/窗口不漂移、上报幂等、打卡 3001/3002/3003、streak 跨天与断签 |
| `ReviewServiceImplTest` | 16 | 到期过滤与排序、stage 递进与封顶、答错重置、错题收录、未选词书不给复习词 |
| `MistakeServiceImplTest` | 23 | 收录原语去重累加、分页与过滤、4001/4002、越权、逻辑删除实证、移除后重新收录 |
| `StudyFlowControllerIntegrationTest` | 17 | 契约 JSON 形状、错误码经 Result 包裹后 HTTP 仍 200、端到端闭环 |

合计 231 个测试全绿（阶段 6 结束时为 143）。

## 5. 待 G6 回写契约的增补项（本阶段新增）

1. `GET /api/client/study/plan` 的 `total` 语义应明确为
   「**今日任务集合大小**（含今日已上报词）」，而非「待完成数」；
   否则会再次诱发缺陷 A。建议在 §3.1 补一句边界说明。
2. §3.1 的 `learned` 口径：**今天是否已上报过该词**（学习或复习都算），
   与「是否历史上学过」不是一回事。
3. 新词候选的「未学过」定义：`word_progress` 中不存在
   `create_time < 今日 00:00 且 learned=1` 的记录。这是对契约原文
   「未学单词」的必要澄清（原文未定义「学」的时点）。
4. §4.1 的 `wrongCount` 取自错题本；无错题记录时补 0（非 null）。
