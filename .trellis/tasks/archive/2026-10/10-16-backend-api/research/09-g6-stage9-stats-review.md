# 阶段 9（统计模块）实现与闸口复核记录

> 契约：《接口详细设计》§7.1 / §7.2；design.md §4.5；implement.md §9。
> 本文件记录实现产出、闸口实测、发现的问题、测试自身的错误，以及待 G6 回写契约的条目。

---

## 1. 产出清单

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `dto/response/HeatmapDay.java` | 已有（阶段 4） | 单日热力点；本阶段起被 `StatsServiceImpl` 实际使用 |
| `dto/response/MasteryPoint.java` | 已有（阶段 4） | 单日掌握度点 |
| `dto/response/HeatmapResponse.java` | 新增 | `{ List<HeatmapDay> days }` |
| `dto/response/MasteryResponse.java` | 新增 | `{ List<MasteryPoint> points }` |
| `common/MasteryRates.java` | 新增 | 掌握度百分比公式——**唯一真相源** |
| `service/StatsService.java` | 新增 | `heatmap(userId, weeks)` / `mastery(userId, days)` |
| `service/impl/StatsServiceImpl.java` | 新增 | 补零 + 分档 + 掌握度；计数全在 SQL |
| `controller/StatsController.java` | 新增 | `/api/client/stats/{heatmap,mastery}` |
| `common/Constants.java` | 修改 | 4 个新常量（默认/上限） |
| `service/impl/AiServiceImpl.java` | 修改 | `buildStudyStats` 改用 `MasteryRates.percent` |
| `resources/mapper/StatsMapper.xml` | 修改 | `selectMasteryDaily` 的 `DATE()` → `CAST(... AS DATE)` |
| `test/java/.../service/StatsServiceImplTest.java` | 新增 | 14 用例 |
| `test/java/.../controller/StatsControllerIntegrationTest.java` | 新增 | 9 用例 |
| `test/java/.../common/MasteryRatesTest.java` | 新增 | 4 用例 |
| `scripts/stage9-stats-smoke.py` | 新增 | 阶段 9 闸口冒烟（29 项，真实 MySQL + 真实 HTTP） |

新增常量（`Constants`）：

```
DEFAULT_HEATMAP_WEEKS = 12      // 契约 §7.1 缺省 12 周
DEFAULT_MASTERY_DAYS  = 30      // 契约 §7.2 缺省 30 天
MAX_HEATMAP_WEEKS     = 52      // 用户可控入参自我约束
MAX_MASTERY_DAYS      = 365
```

---

## 2. 关键设计决策

### 2.1 补零放 Java，不放 SQL

生成连续日期序列在 MySQL 里要靠递归 CTE（`WITH RECURSIVE`）或数字辅助表；
H2 的 MySQL 兼容模式对递归 CTE 的支持与 MySQL 有差异，同一份 SQL 两边跑会出方言问题。
`LocalDate.plusDays` 循环更短、更可控，且让「序列长度」「末点是不是今天」
这类规则能被单测用固定 `Clock` 直接验证。

### 2.2 分档（level）放 Java

`0 / 1–10 / 11–20 / 21+` 是**展示规则**，对应前端 4 档色阶。
写进 SQL 会让 `CASE WHEN` 与色阶耦合；以后前端改 5 档色阶还得动 SQL。

### 2.3 掌握度取「当日快照」

`masteryRate = 已掌握错题 / 总错题 × 100`，是**存量指标**——整条曲线用同一个当前值。
逐日重放「那一天错题本里已掌握几条」需要为每一天重演状态机，而契约公式只认这个存量比例。
`MasteryRates.percent` 同时被 §7.2 与 §9.3（AI 报告）使用，避免两处口径漂移。

### 2.4 两条曲线按不同日期列聚合（刻意区分）

| 曲线 | 日期列 | 理由 |
| --- | --- | --- |
| 热力图（§7.1） | `study_record.check_in_date` | 热力图反映「打卡」行为 |
| 掌握度（§7.2） | `study_record.study_date` | 复习也属学习活动；复习不打卡但应体现在学习曲线上 |

---

## 3. 闸口实测（implement.md §9）

### 3.1 单元/集成测试

```
mvn -o test
Tests run: 277, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

（阶段 8 末为 250，本阶段 +27：StatsServiceImplTest 14 + StatsControllerIntegrationTest 9 + MasteryRatesTest 4。）

### 3.2 闸口冒烟（真实 MySQL 8 + 真实 HTTP）

打包：`target/vocab-backend-1.0.0.jar` = **30,735,816 B**
服务：`java -jar ... --spring.profiles.active=local`，Started in 6.798s

```
python backend/scripts/stage9-stats-smoke.py
== 结果：29 PASS / 0 FAIL ==
```

闸口三条命题逐条兑现：

| 闸口命题 | 兑现方式 | 证据 |
| --- | --- | --- |
| `GET /stats/heatmap` 返回 84 点、日期连续、`level` 匹配分档 | 脚本逐点核对 `level == expected_level(wordCount)`；`assert_contiguous` 证明无断日 | B1–B9（B5 全 84 点一致；B6 卡 2 档上边界 20 词；B9 跨到 3 档 40 词） |
| `GET /stats/mastery?days=30` 返回 30 点、`masteryRate` 与手工 SQL 一致 | 脚本先用真实接口造 2 条错题、标记 1 条已掌握，再与接口返回值比对 | C3 30 点；C6 手工计数 1/2；C7 API = 手工 = 50 |
| `StatsMapper.xml` 无「加载原始记录到 Java」写法 | 逐 `<select>` 审计：全部带 `COUNT/SUM` 聚合或 `LIMIT`；运行期同一天 20 条流水只返 1 个点 | §3.3 |

### 3.3 SQL 聚合证据（真实 MySQL + pymysql 直连）

用 `StatsMapper.xml` 中**逐字复制**的 `selectMasteryDaily` SQL 打真实库：

```
sample user_id=16 records=60 distinct_days=2
  (2026-10-15, learned=20, review=0,  mistake=0)
  (2026-10-16, learned=20, review=20, mistake=1)
```

同一用户两天共 60 条流水 → 只返回 2 行，证明聚合发生在 SQL 而非 Java。
`CAST(create_time AS DATE)` 在真实 MySQL 8.4.9 上正确返回日期。

### 3.4 数据完整性抽查

```
红线 anime_tracker 表数 = 20（未变）
ai_vocab 表数 = 7
word=22761 / word_book=3（种子未受扰动）
learned=1 && next_review_date IS NULL = 0
stage 越界 = 0
study_record 唯一约束重复组 = 0
word_progress 唯一约束重复组 = 0
user_word_book 唯一约束重复组 = 0
阶段 9 测试用户仅 id 23/24/25（stage9* 前缀）
```

### 3.5 回归

| 脚本 | 结果 |
| --- | --- |
| `g4-smoke.py`（阶段 7） | 26 PASS / 0 FAIL |
| `g4-crossday.py`（跨天，自包含） | 18 PASS / 0 FAIL |
| `g5-smoke.py`（阶段 8） | 24 PASS / 0 FAIL |

### 3.6 日志核查

- ERROR 级日志 = **0**（`grep -E "^[0-9-]+ [0-9:.]+ ERROR"` 计数 0）。
- 敏感信息：AppSecret / AppID / MySQL 密码 / JWT secret 明文均 **0** 命中。
- 仅出现 BCrypt 哈希（本地 profile `mapper=DEBUG` 的 MyBatis 参数日志），
  **属已知待办**（阶段 11 将生产默认改 `INFO`），非本阶段引入。

---

## 4. 发现的问题

### 4.1 问题一（真问题：规则一致性违反）——`DATE()` 是 MySQL 专有函数

- **位置**：`StatsMapper.xml` `selectMasteryDaily` 子查询
  `SELECT user_id, DATE(create_time) AS mistakeDate ... GROUP BY user_id, DATE(create_time)`。
- **为何是问题**：本项目红线明确「不写 `CURDATE()`/`DATE_SUB`/`DATE()` 等 MySQL 专有函数，
  时间边界由 Java 传参」，目的是让 H2 测试与 MySQL 生产同语义。
  更尴尬的是**同一个文件的头部注释自己就声明了这条规则**，
  而下方 SQL 却违反它——属于「文档说一套、代码做一套」。
- **为何测试没抓到**：H2 在 `MODE=MySQL` 下**恰好也支持** `DATE()`，
  所以测试全绿；但这是方言红利，不是设计正确性。
- **修复**：改为 SQL-92 标准的 `CAST(create_time AS DATE)`。
  MySQL 8 与 H2 都支持，并在原处补了「为什么不用 DATE()」的注释。
  真实 MySQL 8.4.9 实测 `CAST(create_time AS DATE)` 返回正确日期（§3.3）。
- **说明**：这是阶段 9 新增 SQL 时带进来的、**测试无法暴露**的规则违反，
  靠「逐 `<select>` 审计 + 红线复查」的人工闸口发现——正是闸口存在的意义。

### 4.2 问题二（测试自身错误，非实现缺陷）——类型不匹配

`StatsServiceImplTest` 第 206 行 `seedStudyRecord(TODAY, 0, ...)` 传 `int` 字面量，
而方法签名是 `Long wordId`，编译报 `int 无法转换为 java.lang.Long`。
改为 `0L`。这是**测试代码**的写法疏忽，不是产品缺陷。

---

## 5. 待 G6 回写契约的条目（阶段 9 新增）

1. **§7.1 `days` 恒为连续日期序列**：长度 == `weeks × 7`，末点为服务端「今天」，
   中间无数据的日期**补零**（`wordCount=0`、`level=0`），不是只返回有数据的日期。
2. **§7.1 `level` 分档口径**：`0`=未打卡（词数 0）/ `1`=1–10 / `2`=11–20 / `3`=21+；
   由后端计算，前端直接用。注意「词数 0」与「当天未打卡」在本契约里归为同一档。
3. **§7.1 `weeks` 缺省 12、上限 52**；非法值（≤0 / 非数字）分别回落 12 / 报 1005。
4. **§7.2 `points` 恒为连续日期序列**：长度 == `days`，末点为服务端「今天」，补零同 §7.1。
5. **§7.2 `masteryRate` 取当日快照（存量指标）**：整条曲线使用同一个「当前」值
   （`已掌握错题 / 总错题 × 100`，四舍五入整数），不是逐日重放的历史值。
6. **§7.2 无错题（分母为 0）时 `masteryRate = 100`**，语义为「没有未掌握的薄弱点」，
   而非 0。此口径与 §9.3 AI 报告的 `masteryRate` 完全一致（共用同一实现）。
7. **§7.2 `days` 缺省 30、上限 365**；非法值回落 30。
8. **热力图按 `check_in_date` 聚合、掌握度按 `study_date` 聚合**——
   两条曲线口径刻意不同（复习不打卡但要体现在学习曲线上）。
9. **修正 §7.2 示例中的 `mistakeCount` 含义**：为「当日新增错题条数」（按
   `mistake_entry.create_time` 的日期聚合），不是累计值。
10. **补充：统计接口鉴权失败为 HTTP 401 + 1006**（与其它 `/api/client/**` 一致）。

---

## 6. 复现命令

```bash
# 打包
cd backend && mvn -o -q clean package -DskipTests

# 起服务（真实 MySQL）
java -jar target/vocab-backend-1.0.0.jar --spring.profiles.active=local

# 单元/集成测试
mvn -o test

# 阶段 9 闸口冒烟
python backend/scripts/stage9-stats-smoke.py

# 回归
python backend/scripts/g4-smoke.py
DBHOST=127.0.0.1 DBUSER=root DBPASS=<pwd> DBNAME=ai_vocab python backend/scripts/g4-crossday.py
python backend/scripts/g5-smoke.py
```
