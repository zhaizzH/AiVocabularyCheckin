# 阶段 6（词库模块 + 种子导入）与闸口 G3 复核记录

- 阶段：`10-16-backend-api` 第 6 阶段
- 闸口：**G3 — 三本词书入库且行数/计数严格正确**
- 结论：**通过**。词书 7508 / 5651 / 9602 全部精确命中；契约 §2 四接口端到端实测通过；143 测试全绿。

---

## 1. 本阶段发现并修复的 3 个真实缺陷

不是「写完就过」——下面每条都是测试/实测先失败、定位到真因后才修的。

### 缺陷 1：整份文件全坏行时，库里残留 0 词空壳词书

- **暴露方式**：`BookControllerIntegrationTest#importAllBadLinesReturns2004`
  断言「导入失败不得留下空词书」，实测 `word_book` 里却有 1 行。
- **根因**：`importFromStream` 在解析**之前**就 `insert` 词书；
  发现 `success == 0` 再抛 2004。方法上的 `@Transactional`
  只在调用方本身也开着事务时才回滚——Controller 调用时没有外层事务，
  **词书插入已经是独立提交**，异常回滚不掉它。
- **修复**：把「是否存在合法行」与「创建词书」合并成一步——
  惰性扫描到第一条合法行才 `insert` 词书，再把它连同首行一起写入。
  全坏行输入在任何时刻都没有产生过数据库写入。
- **为何不是「改测试断言」**：测试是对的。契约 §8.1 的 2004 语义是
  「这份文件用不了」，用户预期是「什么都没发生」。残留空词书会让管理端
  多出一本 0 词的、点进去空白的词书——这是用户可见的脏数据。

### 缺陷 2：`importFromStream` 的 success 与 word_count 可能不同源

- **暴露方式**：代码审查时对照 design.md §7.3「不靠自增累加，避免漂移」。
- **根因**：`success += batch.size()` 累加的是**提交给 mapper 的行数**，
  而 `word_count` 来自 `COUNT(*)`。正常路径两者相等，但一旦批量语句
  因某行超宽等被数据库拒绝（理论上可能），两个数字就会分叉，
  而响应里的 `success` 恰恰是管理员用来判断「导入是否完整」的字段。
- **修复**：`success` 也取 `correctWordCount()` 的返回值（与 `word_count` 同源）。
  现在「响应 success」「库中 word_count」「实际 COUNT(*)」三者恒等。

### 缺陷 3：测试用例 `listBooksReflectsUserState` 断言与设计不符

- **暴露方式**：`$.data[?(...)].wordCount` expected `<2>` but was `<0>`。
- **根因**：测试只 `insert` 了 2 个 `word` 行，却没设置 `word_book.word_count`。
  而实现（正确行为）是从词书元数据读 `wordCount`，不是实时 `COUNT(*)`。
- **修复**：测试显式 `wordBookUpdateCount(bookId, 2)`，并新增一条
  `listBooksExposesBookWordCount` 用例专门锁定「wordCount 取自元数据」。
- **顺带澄清的设计问题**：`wordCount` 为什么不做实时 COUNT？
  列表返回 N 本书就是 N 次 COUNT（N+1）。词库是只读资产、
  写入只发生在导入时（已校准），元数据值是可信的。契约 §2.1 的字段语义
  就是「词书单词总数」，非「未删除且未过期的词数」。

---

## 2. G3 闸口实测数据

```
word_book:
  (6, '四级核心词汇', 'CET4', 7508, deleted=0)
  (7, '六级核心词汇', 'CET6', 5651, deleted=0)
  (8, '考研核心词汇', 'KAOYAN', 9602, deleted=0)

每本实际 COUNT(*):  6→7508   7→5651   8→9602      （word_count == actual 全等）
word 总行数:        22761  = 7508 + 5651 + 9602   （无丢行、无重复导入）
```

**时间列非空校验**（验证 XML 里的显式 `NOW()` 生效，因为手写 XML 绕过 `MetaObjectHandler`）：

```sql
SELECT COUNT(*) FROM word WHERE create_time IS NULL OR update_time IS NULL;
-- → 0
```

**映射抽查**（`book_id=6` 前 3 行）：

| word | phonetic | translation | example | definition | audio_url |
|---|---|---|---|---|---|
| abruptly | ə'brʌptlɪ | adv. 突然地 | The path ends off abruptly. | NULL | NULL |
| absorb | əb'sɔ:b | v. 吸收（液体、气体等） | Plants absorb nutrients from the soil. | NULL | NULL |
| abuse | ə'bjuːz | v. 滥用，虐待 | Williams abused his position as Mayor to give… | NULL | NULL |

**缺失字段统计与源文件缺失数逐一比对（证明解析无凭空造值、无丢字段）**：

| 词书 | 空音标 | 空例句 | 空释义 | 总行数 |
|---|---|---|---|---|
| CET4 | 66 | 164 | 0 | 7508 |
| CET6 | 50 | 131 | 0 | 5651 |
| KAOYAN | 17 | 205 | 0 | 9602 |

（与阶段 1 落盘时统计的 `缺 uk CET4=66/CET6=50/KAOYAN=17`、
`缺 sentence CET4=164/CET6=131/KAOYAN=205` **逐项一致**）

**幂等性**：第二次运行 `--vocab.seed=true`，日志输出
「种子词书已存在，跳过」×3，`word_book` 仍 3 行、`word` 仍 22761 行。

**红线**：`anime_tracker` 库 20 表，全程未变。

---

## 3. 契约 §2 四接口端到端实测（真实 MySQL + 真实 HTTP，非 MockMvc）

| # | 用例 | 期望 | 实测 |
|---|---|---|---|
| 1 | `GET /books` 未选时 | 三本，`selected=false`/`learnedCount=0` | ✅ |
| 2 | `GET /books?category=CET6` | 仅 1 本 | ✅ |
| 3 | `GET /books?category=cet4` | 小写命中 | ✅ |
| 4 | `GET /books?category=TOEFL` | `code=0` + `data=[]` | ✅ |
| 5 | `GET /books/{id}?page=1&size=3` | `total=5651`，3 条 | ✅ |
| 6 | `GET /books/999999` | `2001 词书不存在` | ✅ |
| 7 | `POST /books/7/select` | `{bookId:7,selected:true}` | ✅ |
| 8 | 重复 select | `2003 词书已加入` | ✅ |
| 9 | select 不存在的书 | `2001` | ✅ |
| 10 | select 后列表 | `selected=true` | ✅ |
| 11 | 造 1 条 `word_progress` 后列表 | `learnedCount=1` | ✅ |
| 12 | `DELETE /books/7/select` | `{selected:false}` | ✅ |
| 13 | 再次 deselect | `2002 未选择该词书` | ✅ |
| 14 | **移出词书后历史保留** | `word_progress` 仍 1 行 | ✅ |
| 15 | 分页 `page=0&size=0` | 回落 `1/20` | ✅ |
| 16 | 分页 `size=3000` | 截为 `500` | ✅ |
| 17 | `page=99999` | `total` 正确、`list=[]` | ✅ |
| 18 | 普通用户访问 `/api/admin/**` | HTTP 403 + `1007` | ✅ |
| 19 | 无 token | HTTP 401 + `1006` | ✅ |
| 20 | 中文 UTF-8 逐字节 | hex 与源字符串相同 | ✅ |

第 14 条是本阶段最容易被实现错的一条：契约 §2.4 明文「历史保留」。
实现上 `deselect` 只 `DELETE user_word_book` 关联行，
绝不级联删除 `word_progress` / `study_record` / `mistake_entry`。
实测确认移出后 `word_progress` 仍在，重新选书即可无缝续学。

第 20 条用 `.encode('utf-8').hex()` 做逐字节比对，而不是肉眼比对
（`e59b9be7baa7e6a0b8e5bf83e8af8de6b187` == `四级核心词汇` 的 UTF-8 编码）：
MySQL 连接串 `characterEncoding` 若写错，中文会静默变成乱码或 `?`，
只有字节级断言能抓到。

---

## 4. 测试与红线自查

- `mvn -o clean test` → **143 tests, 0 failures, 0 errors**（阶段 5 为 118，+25）。
  - `WordImportServiceImplTest`：11 例（解析、坏行、批边界、COUNT 校正、2004、分类白名单）
  - `BookControllerIntegrationTest`：14 例（§2.1~§2.4 全路径 + 权限 + 分页 + UTF-8）
- 源码红线自查：
  - `grep -rn "System\.out|printStackTrace|System\.err" backend/src/main/java` → **无匹配**
  - `grep -rnE "(password|secret|appid)\s*[:=]\s*\"[^\"]{6,}\""` main/java + `application.yml` → **无匹配**
  - Controller 无业务逻辑、不返回实体（`BookController` 仅取 `UserContext.getUserId()` 后转 Service）
  - 列表接口分页；聚合走 SQL `GROUP BY`；无空 catch

---

## 5. 遗留 / 移交

- `docs/接口详细设计.md` **待 G6 回写**的内容又多了两条本阶段实测确认的边界：
  1. `GET /api/client/books?category=<非法值>` 返回**空数组**（不是 1005），
     而 `POST /api/admin/books/import` 的 `category` 非法返回 **1005**。
     两处行为不同，必须在文档里写清，否则联调会踩。
  2. `importFromStream` 整体失败时**不产生任何副作用**（不建词书）——
     这是对 §8.1「整体格式非法返回 2004」的语义补完。
- 阶段 10 的三个管理端方法（`adminListBooks` / `deleteBook` / 导入）在
  `WordBookServiceImpl` 中已就绪，待阶段 10 接 `AdminBookController`。
- `Definition`/`audio_url` 一期恒为 NULL，已被测试与实测双重确认，属设计预期。
