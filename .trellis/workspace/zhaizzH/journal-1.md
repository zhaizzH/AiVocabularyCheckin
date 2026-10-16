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
