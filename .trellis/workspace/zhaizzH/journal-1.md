# Journal - zhaizzH (Part 1)

> AI development session journal
> Started: 2026-10-16

---

## 2026-10-16 — Bootstrap Guidelines 完成

- 依据 `docs/` 下需求规格说明书确定技术栈：`backend/`（Spring Boot 3 + MyBatis-Plus + MySQL 8）、`ai-service/`（FastAPI + LangChain，无状态、不连库）、`miniprogram/`（微信原生）、`frontend/`（React 18 + TS + Vite + AntD，由 web/ 改名）。
- 填写 11 个 spec 文件（backend 5 + frontend 6），全部中文。核心约定：Result 包装 + 错误码分段、AI 降级一等路径、services 层统一请求、LambdaQueryWrapper 优先、密钥仅环境变量。
- 状态管理按团队决定保持灵活，不指定库。
- `guides/` 三个思考指南已翻译为中文；删除 Trellis 项目专属章节（模板注册、版本文档、模式探测等与本项目无关），保留通用原则并新增本项目边界映射。
- 任务已归档至 `.trellis/tasks/archive/2026-10/00-bootstrap-guidelines`（auto-commit 未生效：仓库尚无提交记录）。

## 2026-10-16 — S1-S3 文档 V1.1 修订

- 任务 `10-16-docs-v2-revision`（轻量，PRD-only）。
- S1：补管理端 React 技术栈与 JWT/响应包装决策、新增双前端差异风险(5)、文末新增版本表 V1.0/V1.1。
- S2：§4.1/4.2 管理端改为 React18+TS+Vite+AntD5、补 MyBatis-Plus/JWT/BCrypt；§4.3 补 Result 包装与错误码分段；§4.4 补 React 可行性；§5.3 指认 S3 §4 接口表；§5.4 补降级提示文案与接口一致性需求；§8 补鉴权约束。
- S3：版本表追加 V1.1；§8.2 注明管理端技术栈已确定。
- 全文统一术语“网页管理端”，消除“HTML/CSS/JavaScript”与“网页端”旧表述。



## Session 1: backend 阶段 2：工程骨架（Spring Boot 3.5 + MyBatis-Plus）
<!-- trellis-session: v=2 fp=0809ec98d4f0f187 -->

**Date**: 2026-10-16
**Task**: backend 阶段 2：工程骨架（Spring Boot 3.5 + MyBatis-Plus）
**Branch**: `main`

### Summary

完成 backend 阶段 1（建库+词库落盘）与阶段 2（工程骨架）。37 个单测全绿，构建与启动实测通过，并修掉兜底异常处理器吞 404 的隐患。

### Main Changes

- 阶段 1：data/vocab-source/ 落盘 CET4(7508)/CET6(5651)/KAOYAN(9602)+LICENSE；scripts/fetch-vocab.sh 带行数校验
- 阶段 2：pom.xml（Spring Boot 3.5.6/Java 17/MyBatis-Plus 3.5.9+jsqlparser/jjwt 0.12.6/security-crypto/h2）
- common/ 六个类：Result（成功码 "0"）、ErrorCode（1xxx-9xxx 分段）、BizException、PageResult、Constants、GlobalExceptionHandler
- config/ 七个类：MybatisPlusConfig、JwtUtil、JwtInterceptor、UserContext、UserStatusValidator、WebMvcConfig、RestTemplateConfig
- 安全加固：JWT_SECRET 移除默认值改为 fail-fast；CORS 白名单参数化；local 配置走 .example 模板
- 修隐患：兜底异常处理器曾把 404 吞成 200+9001，现 404/405/415 保留真实 HTTP 语义
- 契约增补（待 G6 回写）：补 1006 TOKEN_INVALID / 1007 FORBIDDEN / 9002-9004，见 research/01-contract-gaps-stage2.md

### Git Commits

| Hash | Message |
|------|---------|
| `140e601` | feat(backend): 阶段 2 工程骨架 — Spring Boot 3.5 + MyBatis-Plus 基础框架 |

### Testing

- [OK] mvn clean package 通过；mvn -o 离线构建通过；产物 30.6MB jar
- [OK] 37 个单测全绿：ResultAndErrorCodeTest(11)/JwtUtilTest(7)/JwtInterceptorIntegrationTest(11)/GlobalExceptionHandlerIntegrationTest(8)
- [OK] 实测：无 token→HTTP 401+1006；缺失 JWT_SECRET→启动失败(fail-fast)；仅给 JWT_SECRET→正常启动；CORS 预检→200
- [OK] 规范自查：无 System.out.println、无空 catch、无硬编码密钥/URL、日志无敏感值、依赖树确认无 spring-boot-starter-security

### Status

[OK] **Completed**

### Next Steps

- 阶段 3：数据库迁移脚本 V001__init.sql（6 表）+ V002__word_progress.sql，验证可重复执行 → 闸口 G2
- 阶段 4-11：实体+Mapper → 认证（阶段5接入 UserStatusValidator 真实实现）→ 词库+种子导入（G3）→ 学习/复习/错题（G4）→ AI 降级（G5）→ 统计 → 管理（先回写契约 G6）→ 收尾（G7）

---

## 2026-10-16 — backend-api 阶段 4：实体与 Mapper

### Goal

7 张表建 MyBatis-Plus 实体 + Mapper + 统计聚合 XML，过闸口（MyBatis-Plus 能读写 7 张表）。

### Done

- entity/ 7 个：User / WordBook / Word / UserWordBook / StudyRecord / MistakeEntry / WordProgress
  - 时间列统一标 @TableField(fill=INSERT / INSERT_UPDATE)（G2 去 DB 默认值后的必填约束）
  - 6 表 @TableLogic deleted；user_word_book 物理删除、无 deleted（全项目唯一）
- mapper/ 7 个 BaseMapper + StatsMapper；resources/mapper/StatsMapper.xml（7 个查询，逐个手写 deleted = 0）
- config/JacksonConfig：JSR-310 全局格式 yyyy-MM-dd HH:mm:ss / yyyy-MM-dd
  （spring.jackson.date-format 对 JSR-310 无效，漏配会让 createTime 输出带 T 的 ISO 串）
- dto/response/HeatmapDay、MasteryPoint
- 测试 EntityMappingIntegrationTest（H2，19）+ RealMysqlMappingIT（真实 MySQL，默认跳过）
- scripts/verify-schema-entities.py（实体↔真实库双向核对）、verify-h2-schema-parity.py（H2↔V001/V002 漂移检测）

### 修复的真实缺陷

1. JDBC URL characterEncoding=utf8mb4 → Connector/J 用 Charset.forName 解析，utf8mb4 非法 →
   连接直接失败（Unsupported character encoding 'utf8mb4'）。阶段 2 起潜伏，因从未真正连库而未暴露。
   三处 URL 改 UTF-8。
2. StatsMapper.xml 用 MySQL 专有 DATE_SUB(CURDATE(),...) → H2 报 Function "date_sub" not found。
   改为起始日期由 Java 计算后作 since 参数传入：H2 与真实 MySQL 执行同一份 SQL，
   且「回溯多少天」显式落在 Service 层。
3. JwtUtilTest.tamperedTokenReturnsNull 是 flaky 测试：原断言改签名段末字符，
   而 32 字节 HMAC 的 base64url 末字符低 2 位是填充位、解码时丢弃 → 实测 6.0% 概率
   改写后签名字节完全相同。改为篡改 payload 首字符；连跑 12 次全绿。

### 设计修正

- 热力图按 check_in_date 聚合（S3 §7.5 + 需求清单 §5.1 + 接口设计 §7.1 三处一致）；
  design.md §4.5 草稿误写 study_date，已改并补注「复习不打卡但属学习活动」（掌握度用 study_date）。
- 掌握度 mistakeCount 改 COALESCE(MAX(...))，GROUP BY 只按 study_date，消除按关联聚合值分组的脆弱写法。
- 删除冗余 .gitkeep（目录已有真实内容且会打入 jar）；UserWordBookMapper 注释纠正（物理删除无 @TableLogic）。

### 安全

- 校验脚本初版把 DB 密码写成默认值（违反禁硬编码凭证红线），改为无默认值 + 缺密码 fail-fast。
- 该密码曾随研究文档误入提交 23720a3：因无 git remote，已 reset --soft 折叠重提 + reflog expire + gc prune，
  全历史 git grep 三个敏感串均 0 命中，污染对象已不可访问。

### Git Commits

| Hash | Message |
|------|---------|
| `d8581ac` | feat(backend): 阶段 4 实体与 Mapper — 7 表映射 + 统计聚合 |

### Testing

- [OK] mvn -o clean test：56 全绿（阶段 2 的 37 + 本阶段 19），连跑 3 次稳定
- [OK] RealMysqlMappingIT（-Dmysql.it=true）：真实 MySQL 8.4.9 + ONLY_FULL_GROUP_BY 上 7 表
  插入/读回/聚合/update 刷新 updateTime 全通过；事务回滚，7 表精确计数 = 0
- [OK] 掌握度断言经变异测试证明有效（错题关联表改错 → 测试立刻失败）
- [OK] 校验脚本：实体 7 / 67 列双向一致；schema-h2.sql 与 V001/V002 无漂移
- [OK] 红线：anime_tracker 20 表未动；无 System.out / 空 catch / 硬编码密钥

### Status

[OK] **Completed**（阶段 4；本阶段无独立闸口，下一闸口为 G3）

### Next Steps

- 阶段 5 认证模块：AuthService 落地 UserStatusValidator 真实实现（查 user.status，
  使「管理员禁用 → 该用户 JWT 立即失效 1003」生效）+ 真实 AppID/AppSecret 走 code2Session
- 阶段 6 词库与种子导入 → 闸口 G3（三本词书计数 7508/5651/9602 严格一致）
- 阶段 7 学习/复习/错题 → 闸口 G4；阶段 8 AI 降级 → G5；阶段 10 前回写契约 → G6；阶段 11 收尾 → G7


## Session 2: backend 阶段 5：认证模块（5 个 auth 接口 + 禁用即时生效）
<!-- trellis-session: v=2 fp=1fc470090199925e -->

**Date**: 2026-10-16
**Task**: backend 阶段 5：认证模块（5 个 auth 接口 + 禁用即时生效）
**Branch**: `main`

### Summary

实现《接口详细设计》§1 全部 5 个认证接口，并落地 §8.7「管理员禁用后 token 立即失效」。UserStatusValidator 升为三态枚举以区分 1003 与 1006。118 测试全绿（+62），真实 MySQL + 真实微信 API 冒烟 38/38。

### Main Changes

- dto/request/ 4 个 + dto/response/ 4 个 + service/AuthService(+Impl) + controller/AuthController（5 接口）
- client/WxApiClient：真实 code2Session，专用 wxRestTemplate（5s 读超时）；未配置/S网络错/errcode!=0/缺 openid/非 JSON 统一 → 1004，绝不冒泡 9001
- UserStatusValidator 由单方法 isActive(Long) 升为三态枚举 ACTIVE/DISABLED/UNKNOWN；新增 UserStatusValidatorImpl 每请求主键查库（@TableLogic 自动附 deleted=0）
- JwtInterceptor：DISABLED→401+1003、UNKNOWN→401+1006；管理端非 ADMIN→403+1007
- 唯一性由 DB 唯一索引兜底（非仅先查后插）：并发注册→1001；并发微信建号→重查复用，重查仍无则原样上抛
- 微信账号 username/password_hash 均置 NULL：MySQL 唯一索引允许多 NULL，数据层杜绝其走密码登录
- design.md §5 补全：JWT_SECRET fail-fast（原文「必须有默认值兜底」与红线冲突，已修正）、三态校验、wx 客户端、唯一性并发；新增 §5.5/§5.6
- implement.md：阶段 5 三项勾选 + 实施记录；闸口表 G1/G2 标记 ✅；新增 research/04-stage5-auth-review.md

### Git Commits

| Hash | Message |
|------|---------|
| `8ca2e17` | feat(backend): 阶段 5 认证模块 — 5 个 auth 接口 + 禁用即时生效 |

### Testing

- [OK] mvn -o clean test：118 全绿（阶段2 的 37 + 阶段4 的 19 + 本阶段 62），连跑 3 次稳定
- [OK] 新增 WxApiClientTest(8，起真实本地 HTTP 桩服务，非 mock 方法)：覆盖 URL 拼接/errcode 分支/非 JSON/连接失败/缺凭证不发请求
- [OK] 新增 AuthServiceImplTest(21)：真实库 + 并发分支桩注入（DuplicateKey→重查复用 / 重查无→上抛）
- [OK] 新增 AuthControllerIntegrationTest(25)：MockMvc 真实 HTTP，5 接口外部形状 + §8.7 禁用即时失效
- [OK] 改造阶段 2 两个测试类：原伪造 userId=999 签 token，现会被真实查库判为 UNKNOWN→401，改为先建真实用户
- [OK] 真实 MySQL + 真实微信 API 闸口冒烟 /c/tmp/stage5-smoke.sh 38/38：无效 code 得 errcode=40029→1004（证明 appid/secret 被微信接受）
- [OK] §8.7：直接 UPDATE user SET status=0 后同一 token 立即 401+1003；同账号重新登录也 1003
- [OK] 响应体 UTF-8 逐字节校验：1002 文案字节 e794a8...e8afaf 与期望一致；createTime 输出 2026-10-16 18:11:07（空格分隔）
- [OK] 红线：anime_tracker 仍 20 表、ai_vocab 7 表；user 表回到 0 行；无非 BCrypt 哈希；main 下无 System.out/硬编码凭证；application-local.yml 未被 git 跟踪

### Status

[OK] **Completed**

### Next Steps

- 阶段 6 词库模块 + 种子导入（单一 parseLine 双入口、saveBatch 1000、导入后校正 word_count）→ 闸口 G3（7508/5651/9602 严格一致）
- 阶段 7 学习/复习/错题核心闭环 → G4；阶段 8 AI 降级 → G5；阶段 9 统计（StatsMapper 已就位，补 Service/Controller）
- 阶段 10 管理模块前必须 G6 回写契约：1006/1007/9002-9004 + JSR-310 格式注 + §8/§10 新接口 + S3 三条（表数 6→7、加固唯一索引、时间列 ORM 填充）
