# 复审：实现 vs 文档一致性（2026-10-17，第二轮）

对象：`ai-service/` 全部 35 个源文件 + `tests/` 9 个 + task 四份文档。
方法：逐文件读码 + **对每个疑点跑实证**（不靠阅读推断）。
基线：`pytest -q` → **58 passed**（先确认起点是真绿，不是 skip 出来的假绿）。

---

## 结论速览

| # | 严重度 | 问题 | 证据 |
|---|---|---|---|
| D1 | **P0 缺陷** | 路由用 `except BaseException`，把任务取消（客户端断开/服务关闭）翻译成 502 并打 WARNING 假日志 | 实证：注入 `CancelledError` → `502 ai_service_llm_failed` |
| D2 | **P0 缺陷** | `openai.APITimeoutError` 不是 `TimeoutError` 子类 → 单次尝试超时映射成 **502** 而非 504 | `issubclass(APITimeoutError, TimeoutError)` → `False`；实跑得 `OpenAITimeoutError` |
| D3 | P1 缺口 | `design.md §9.3` 承诺的 `tests/test_live_deepseek.py` **不存在**（默认 skip 的真实路径用例） | `ls` MISS |
| D4 | P1 缺口 | `implement.md` 阶段 7 要求把真实响应/耗时写入 `research/02-live-verification.md`，**文件不存在**（证据只存在于会话中，未落盘） | `ls` MISS |
| D5 | P2 | README 写「当前 56 个用例」，实际 **58** | `pytest --collect-only` |
| D6 | P2 | `tests/test_explain.py` 有未使用 import `HTTPException` | ruff F401 |
| D7 | P2 | `tests/conftest.py` 有死代码 `_startup_hook`（从未被调用） | 读码 |
| D8 | P2 文档 | `design.md §9.2` 表格中 `test_lifespan.py` 重复出现两行 | 行 517 与 520 |
| D9 | P2 文档 | `design.md §3` 目录树缺 `scripts/`（实际有 `verify_live.py` / `e2e_backend.py`） | 对比 |

---

## D1 详解：`except BaseException` 把取消当失败

`app/routers/ai.py` 三个路由均为：

```python
except BaseException as exc:  # noqa: BLE001
    raise _map_exception(exc, "example", ...) from exc
```

`asyncio.CancelledError` 在 Python 3.8+ 继承 `BaseException`，**不**继承 `Exception`（实测
`issubclass(CancelledError, Exception)` → `False`）。因此客户端断开连接、
uvicorn 停机、上层 `asyncio.timeout` 取消传播时：

1. `_map_exception` 落到最后的 `llm_failed_error()` → 502 `ai_service_llm_failed`；
2. 打一条 `WARNING ... exc=CancelledError`，把「正常取消」记成「模型故障」；
3. **最严重**：`CancelledError` 被转换成 `HTTPException` 后**不再向上传播**，
   违背 asyncio 取消协议——任务无法被正常取消，`asyncio.timeout` 的语义被破坏。

实证输出（注入 `CancelledError` 的 Fake Runnable）：

```
WARNING ai_service.routes ai_service.example 调用失败 cost_ms=15 exc=CancelledError
STATUS = 502 BODY = {"detail":"ai_service_llm_failed"}
```

`design.md §7.3` 明确写的是 `except Exception`，**代码偏离了设计**。
修法：`except Exception` + 单独放行 `CancelledError`（`raise` 原样上抛）。

## D2 详解：单次尝试超时被误映射为 502

`design.md §7.3` 写 `except (TimeoutError, openai.APITimeoutError): -> 504`，
代码只判 `(TimeoutError, asyncio.TimeoutError)`（后者在 3.11+ 就是 `TimeoutError` 的别名）。

实测异常继承链：

```
openai.APITimeoutError
  └── APIConnectionError → APIError → OpenAIError → Exception    # 不含 TimeoutError
```

`langchain_openai` 抛出的是 `OpenAITimeoutError`，它是 `APITimeoutError` 的子类。
后果：**单次模型尝试超时（5s 用尽）→ 502**，而 backend 的 5001/5002 界线里
「超时」应当走 5001。虽然 backend 主要靠自己的读超时判 5001，但契约语义上
这是错的，且 `ERR_LLM_TIMEOUT` 这条稳定 detail 在单次超时时**永远不会出现**。

实证：

```
EXC = OpenAITimeoutError | module = langchain_openai.chat_models.base
is TimeoutError   : False
is APITimeoutError: True
```

## D3/D4 详解：两个「文档承诺但未交付」的产物

- `design.md §9.3`：`tests/test_live_deepseek.py`，仅当 `DEEPSEEK_LIVE_TEST=1` 时运行。
  真实调用确实做过（example 1146ms / explain 3698ms / report 2354ms），
  但**可重复执行的用例没有落盘**——下次想复验只能靠重写脚本。
- `implement.md` 阶段 7：「把真实响应与耗时写入 `research/02-live-verification.md`」。
  文件不存在。真实调用的证据目前**只存在于会话记录里**，这是交付物缺失。

两者都不是「代码坏了」，但都是 implement.md 自己列的验收动作，属于未闭环。

---

## 已确认**成立**的部分（逐项实证过，无需改动）

| 项 | 验证方式 | 结论 |
|---|---|---|
| 契约字段逐字一致（3 接口 7 字段） | 读 `schemas/*` + OpenAPI | ✅ |
| `max_tokens` 经 `extra_body`，最终 body 顶层键正确 | `test_llm_request.py` MockTransport | ✅ |
| 顶层 `response_format` + 顶层 `thinking.type=disabled` | 同上 | ✅ |
| `python -c` 验证 `repr(settings)` 不含 key | `SecretStr` | ✅ |
| 缺 key 不启动崩、调用期 503（含真实 lifespan 路径） | `test_lifespan.py` + `test_example.py:123` | ✅ |
| report 快速路径 0 次 LLM、常规路径 2 次 | `test_report.py` 调用计数 | ✅ |
| 快速路径文案不夸大（无「全部学习内容」） | 断言 | ✅ |
| 空错题列表 + 掌握度非 100 → 仍走常规路径 | `test_report.py:75` | ✅ |
| 请求级 deadline 真实取消到模型边界 | 慢 Runnable 收到 `CancelledError` | ✅ |
| report 超时后 `compose` 不启动 | 事件序列断言 | ✅ |
| lifespan 两轮起停、client 每轮新建并 `aclose()` | `test_lifespan.py` 手动 `lifespan_context` | ✅ |
| `asyncio_mode = auto` 存在（防假绿） | `pytest.ini` + 58 用例实跑非 skip | ✅ |
| 红线：AST 无 DB import + requirements 无 DB 包 | `test_no_database_imports.py`（有牙齿，曾注入 `import sqlalchemy` 验证失败） | ✅ |
| `SQLAlchemy` 在 venv 中**未安装** | `importlib.metadata` → `NOT INSTALLED` | ✅ |
| 依赖锁版与实测一致（12 个包逐个比对） | `requirements.txt` vs venv | ✅ |
| 解析容错四步（裸/围栏/夹文本/括号配平） | `test_parsing.py` 10 用例 | ✅ |
| `wrongContext` 空串仍成功 | `test_explain.py:43` | ✅ |
| 提示词含 `json` 字样 + 示例（JSON mode 硬性要求） | `test_llm_request.py:132` | ✅ |
| 提示词不假设有「用户误选项」 | 读 `_EXPLAIN_SYSTEM` | ✅ |
| 日志不记 key / 请求体 / `wrongContext` / prompt 全文 | 读 `logging` + router | ✅ |
| detail 全 ASCII（GBK 安全） | 6 个常量 | ✅ |
| 无 `print()`、无裸 `except`、无可变默认参数 | ruff + 读码 | ✅ |

---

## 需修复清单（按严重度）

1. **D1** `app/routers/ai.py`：`except BaseException` → `except Exception`，并放行 `CancelledError`。
2. **D2** `_map_exception` 增加 `openai.APITimeoutError` → 504。
3. **D3** 新增 `tests/test_live_deepseek.py`（默认 skip，`DEEPSEEK_LIVE_TEST=1` 才跑）。
4. **D4** 新增 `research/02-live-verification.md`，落盘真实响应与耗时证据。
5. **D5** README「56」→ 实际数。
6. **D6/D7** 清理未使用 import 与死代码。
7. **D8/D9** `design.md` §9.2 去重、§3 目录树补 `scripts/`。

D1/D2 属**代码缺陷**，必须修且必须补回归测试（现有 58 个用例全绿也测不出来，
说明覆盖存在缺口）。D3–D9 为交付完整性/文档一致性。
