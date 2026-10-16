# 日志规范

> 本项目的日志记录方式。

---

## 概览

- `backend/`：使用 SLF4J（`private static final Logger log = LoggerFactory.getLogger(X.class)`），
  底层为 `spring-boot-starter-logging` 自带的 Logback。禁止 `System.out.println`，
  禁止直接引用 log4j。
- `ai-service/`：使用 Python 标准库 `logging`，在 `app/core/` 中统一配置一次，
  格式 `%(asctime)s %(levelname)s %(name)s %(message)s`。Uvicorn 访问日志保持开启。

---

## 日志级别

| 级别 | 使用场景 |
|------|----------|
| DEBUG | 本地开发细节（SQL 由 MyBatis-Plus 配置输出，不要手工打印 SQL） |
| INFO | 业务事件：用户注册、打卡创建、词库导入、AI 调用开始/结束（含耗时） |
| WARN | 可恢复的异常：AI 降级触发、大模型重试、登录失败尝试 |
| ERROR | 未处理异常、数据库故障、配置问题。必须带堆栈（`log.error("msg", e)`） |

---

## 日志格式

- 日志消息为简洁的中文或英文短语，上下文用 key=value 形式：
  `log.info("打卡创建 userId={} bookId={} streak={}", ...)`（使用 SLF4J 的
  `{}` 占位符——禁止字符串拼接）。
- 完整堆栈只由全局异常处理器记录一次；Service 不要对同一异常
  既记录又抛出（避免堆栈重复刷屏）。

---

## 应该记录什么

- 每一次 AI 服务调用：功能名、耗时、成功/降级结果——这是需求规格说明书
  可靠性要求的主要观测信号。
- 管理端操作（词库导入、用户禁用/启用）：INFO 级别并带操作者 id。
- 认证事件：登录成功/失败（失败记 WARN，不记录密码）。
- 启动时配置自检（数据源连通、AI 服务地址已配置）。

---

## 不应记录什么

- **密码、密码哈希、JWT token、大模型 API 密钥**——任何级别都不许。
- INFO 级别不记录完整的 AI 请求/响应体（含用户学习数据且体积大）；
  仅在 DEBUG 级别记录，并做截断。
- 高频读接口（单词列表）不要逐请求打 INFO——那是 DEBUG 的职责；
  生产环境保持热点路径安静。
