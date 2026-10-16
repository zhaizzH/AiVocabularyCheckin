# 阶段 4 复核记录：实体 / Mapper / 统计聚合

> 闸口：MyBatis-Plus 能读写 7 张表。结论：**通过**（54 测试全绿 + 真实 MySQL 集成测试通过）。
> 本文件另外记录**两个在阶段 4 才暴露的真实缺陷**及其修法，以及新增的两个常驻校验脚本。

## 1. 产出清单

| 类别 | 文件 |
| --- | --- |
| 实体 | `entity/` 下 7 个：`User` / `WordBook` / `Word` / `UserWordBook` / `StudyRecord` / `MistakeEntry` / `WordProgress` |
| Mapper | `mapper/` 下 7 个 `BaseMapper<T>` + `StatsMapper` |
| XML | `resources/mapper/StatsMapper.xml`（热力图 / 掌握度 / 计数共 7 个查询） |
| 配置 | `config/JacksonConfig.java`（JSR-310 全局格式） |
| DTO | `dto/response/HeatmapDay` / `MasteryPoint` |
| 测试 | `entity/EntityMappingIntegrationTest`（H2，17 用例）+ `entity/RealMysqlMappingIT`（真实 MySQL，默认跳过） |
| 测试资源 | `test/resources/schema-h2.sql`（V001/V002 的 H2 等价翻译） |
| 校验脚本 | `backend/scripts/verify-schema-entities.py`、`backend/scripts/verify-h2-schema-parity.py` |

## 2. 缺陷一：JDBC `characterEncoding=utf8mb4` 导致连接失败（真实缺陷，阶段 2 起潜伏）

**现象**：首次真正连库（阶段 4 的 `RealMysqlMappingIT`）即失败：

```
java.sql.SQLException: Unsupported character encoding 'utf8mb4'
  ← com.mysql.cj.exceptions.WrongArgumentException
  ← java.io.UnsupportedEncodingException: utf8mb4
```

**根因**：Connector/J 用 `java.nio.charset.Charset.forName()` 解析
`characterEncoding` 的值，而 **`utf8mb4` 是 MySQL 的字符集名、不是合法 Java 字符集名**。
实测（JDK 21）：

```
OK   UTF-8
OK   utf8
FAIL utf8mb4
```

**为何潜伏到阶段 4**：阶段 1–3 只建库、跑 DDL、查 `INFORMATION_SCHEMA`（用 pymysql，
不经 Connector/J），**没有任何 Java 代码真正建立过连接**，所以该 URL 从未被执行到。

**修法**：三处 URL 改为 `characterEncoding=UTF-8`
（`application.yml` / `application-local.yml` / `application-local.yml.example`）。
Connector/J 8 会据此与服务端协商 `utf8mb4`，中文与 emoji 均可正常存取。

**结论**：这类「配置写错但长期不报错」的问题，只有**真正连一次真实数据库**才暴露——
这本身也是对「只跑 H2 单测不够」的佐证（见下节）。

## 3. 缺陷二：H2 不支持 `DATE_SUB` / `CURDATE`

**现象**：

```
org.h2.jdbc.JdbcSQLSyntaxErrorException: Function "date_sub" not found
```

**根因**：`StatsMapper.xml` 原按 MySQL 写法用 `DATE_SUB(CURDATE(), INTERVAL 84 DAY)`
计算起始日期；H2 的 MySQL 兼容模式**不实现**这两个函数。

**候选方案与取舍**：

| 方案 | 问题 |
| --- | --- |
| 在 H2 里注册自定义别名函数 | 测试侧造假，生产 SQL 仍与测试不同方言，等于没验 |
| 改写成 H2 与 MySQL 都支持的函数组合 | 两者交集里没有等价的「减天数」函数，无法保证语义一致 |
| **（采用）起始日期由 Java 计算后作为 `since` 参数传入** | 无 |

**采用方案的好处**（已写入 `StatsMapper` 类注释与 design.md §4.5）：

1. SQL 不再依赖 MySQL 专有日期函数 → **H2 与真实 MySQL 执行同一份 SQL**，
   消除「测试通过、生产报错」的方言偏差。
2. 「回溯多少天」这条业务规则从 SQL 里**显式提到 Service 层**，
   单测可注入固定日期，不依赖运行时的系统日期。
3. 顺带把 `DATE_SUB` 那个魔法数字 `84`（= 12 周 × 7）改为由 `weeks` 参数推导，
   与契约 `?weeks=12` 的入参语义对齐。

## 4. 为什么「`mvn test` 全绿」不足以作为本阶段闸口

`mvn test` 跑在 H2 + **手写的** `schema-h2.sql` 上。手写翻译意味着一个静默风险：
**V001/V002 改了、H2 忘了同步**——此时测试仍全绿，却在验证一个与生产不同的结构。

因此本阶段补了**三重独立验证**，把「测试环境的假象」压到最小：

| 验证 | 独立于什么 | 查什么 |
| --- | --- | --- |
| `RealMysqlMappingIT` | 独立于 H2 | 真实 MySQL 上插入不设时间戳的实体，证明 `@TableField(fill=...)` 兜住了 `1364` |
| `verify-schema-entities.py` | 独立于 MyBatis/Spring（正则解析源码 + pymysql 直连） | 实体字段 ↔ `INFORMATION_SCHEMA` 列**双向**核对；`fill` / `@TableLogic` 标注；NOT NULL 无默认值列是否都有字段承载 |
| `verify-h2-schema-parity.py` | 独立于运行时 | `schema-h2.sql` ↔ `V001/V002` 的表/列/类型/可空性/唯一约束是否漂移 |

两个脚本都放在 `backend/scripts/`，可重复执行，属于**常驻回归防线**
（后续任何 DDL 调整都应先跑 `verify-h2-schema-parity.py`）。

> 脚本自身也踩过一次坑并修正：`verify-h2-schema-parity.py` 第一版把
> `CREATE INDEX`（普通查询索引）也当成唯一约束统计，误报 6 处「漂移」。
> 修正为只统计 `CREATE UNIQUE INDEX` 后才与实际一致——
> 这提醒：**校验脚本报错时，先怀疑脚本本身**，不要直接去「修」被校验的对象。

## 5. 缺陷三：`JwtUtilTest.tamperedTokenReturnsNull` 是 flaky 测试（~6% 概率误报）

**发现经过**：阶段 4 收尾重跑全量构建时，`JwtUtilTest` 突现
`expected: <null> but was: <{sub=42, ...}>`——而此前多次构建均绿。
同一份代码时绿时红，**是 flaky 而非功能回归**。

**根因**：原测试篡改 token 的**最后一个字符**：

```java
String tampered = token.substring(0, token.length() - 1)
        + (token.endsWith("A") ? "B" : "A");
```

32 字节 HMAC-SHA256 签名 → base64url 编码为 43 个字符，
承载 $43 \times 6 = 258$ 位，而原始数据只有 $32 \times 8 = 256$ 位——
**多出的 2 位是填充位，解码时被丢弃**。
因此若只改动末字符中落到「填充位」的那些比特，
**解码出的签名字节与原始完全相同**，校验自然通过、测试失败。

**实测确认**（模拟 2000 次随机签名）：

```
原断言（改末字符）改写后签名仍相同的比例: 120/2000 = 6.0%  -> 必然 flaky
```

**修法**：改为篡改 **payload（第二段）的首字符**。
payload 是签名计算的输入，改动它签名必然不匹配，且与随机字节无关：

```java
String[] parts = token.split("\\.");
char first = parts[1].charAt(0);
parts[1] = (first == 'A' ? 'B' : 'A') + parts[1].substring(1);
String tampered = parts[0] + "." + parts[1] + "." + parts[2];
assertNotEquals(token, tampered, "篡改后必须与原 token 不同");
assertNull(util.parseToken(tampered));
```

并加了 `assertNotEquals(token, tampered)` 前置断言，
防止「篡改后居然等于原串」这类退化情形（即在断言之前就把「确实改了」钉住）。

**验证**：`JwtUtilTest` 连跑 **12 次全部通过**（修复前约 6% 概率失败）。

**教训**：这个测试从阶段 2 就存在，但因「大约 94% 的情况恰好能过」而不易暴露。
它提醒两件事：
1. 与随机数/哈希相关的失败用例，**断言必须建立在输入变化上**
   （改输入 ≥ 改输出的冗余位）。
2. 收尾时要多跑几次全量测试，单次绿灯不足以证明无 flaky。

## 6. 安全复核

- 校验脚本初版曾把数据库密码写成 `os.environ.get("DB_PASSWORD", "<真实密码>")` 默认值，
  **违反「禁硬编码凭证」红线**，已改为无默认值 + 缺密码时 fail-fast（exit 2）。
  提交前用 `git diff --cached | grep` 扫过 DB 密码 / AppSecret / AppID，均 0 命中。
  （本文档同样不写密码明文：叙述缺陷时用占位符，避免把敏感值再次写进仓库。）
- `application-local.yml`（含真实 DB 密码与微信 AppSecret）确认未被 git 跟踪、
  历史上也从未提交，且被 `.gitignore:5` 覆盖。

## 7. 给后续闸口的待办

- **G6 回写契约**（沿用阶段 2 清单，另有新增）：
  1. `docs/接口详细设计.md` §0 错误码表补 `1006` / `1007` / `9002`~`9004`，
     并补一句「HTTP 4xx 保留给路由层错误，业务失败一律 HTTP 200 + code」。
  2. §0「字段命名」需明确 **JSR-310 的全局格式**（`YYYY-MM-DD HH:mm:ss` /
     `YYYY-MM-DD`）——否则 `createTime` 可能被实现为带 `T` 的 ISO 串。
     本阶段已用 `JacksonConfig` 落地，契约需与之对齐。
  3. S3 §7 需补 3 条注：表数 6 → 7（新增 `word_progress`）、
     `study_record` / `user_word_book` 的加固唯一索引、时间列改由 ORM 填充的语义。
  4. 新增待记：`application.yml` 的 `wx.code2session-url` 已参数化
     （`WX_CODE2SESSION_URL`），若交付文档有「配置项清单」需同步。
- **阶段 5 前置**：`UserStatusValidator` 的真实实现（查 `user.status`）应在认证模块落地，
  使「管理员禁用 → 该用户 JWT 立即失效（1003）」生效。
