# 数据库规范

> 本项目的数据库约定与实践。

---

## 概览

- **数据库**：MySQL 8.0，InnoDB 引擎，全库使用 `utf8mb4`。
- **访问层**：MyBatis-Plus（基于 MyBatis）。不使用 JPA，不写原生 JDBC。
- **只有 `backend/` 可以连接 MySQL。** `ai-service/` 是无状态服务，
  没有任何数据库连接——永远不许添加。
- 核心表（依据需求规格说明书）：用户表、词书/单词表、学习记录表（打卡）、
  错题表，以及多对多关系的中间表（用户 ↔ 已选词书等）。

---

## 查询模式

- 动态条件优先使用 `QueryWrapper` / `LambdaQueryWrapper`；
  **默认使用 `LambdaQueryWrapper`**，避免重构时字符串列名静默失效。
- 仅当 Wrapper 确实无法表达时才写 Mapper XML
  （如带聚合的多表联查——打卡热力图统计）。
- 所有列表接口必须使用 MyBatis-Plus `Page` 分页——绝不返回无上限列表，
  管理端接口也不例外。
- 批量操作使用 `saveBatch` / `removeByIds`；禁止循环单条插入。
- 统计类查询（热力图、掌握度曲线）在 SQL 中完成聚合，而不是把原始记录
  加载到 Java 内存里再计算。

---

## 迁移管理

- 数据库结构以**纯 SQL 脚本**管理，存放在 `backend/src/main/resources/db/`
  （课设交付物要求提供可导出的数据库脚本）。暂不使用 Flyway/Liquibase——
  每次变更新增一个有序脚本：`V001__init.sql`、`V002__add_mistake_book.sql`……
- 每张表必须包含：`id`（BIGINT 自增主键）、`create_time`、`update_time`
  （DATETIME，由 MyBatis-Plus `@TableField(fill = ...)` 自动填充）。
- 面向用户的数据（用户、学习记录、错题）使用逻辑删除
  （`deleted` 字段 + `@TableLogic`）；物理删除仅限中间表记录。

---

## 命名规范

- 表名：`snake_case`，同一族表单复数保持统一
  （`user`、`word_book`、`word`、`study_record`、`mistake_entry`）。
- 列名：`snake_case`（`user_id`、`check_in_date`、`streak_days`）。
- 索引：`idx_<表名>_<列名>`；唯一键 `uk_<表名>_<列名>`。
- 外键：仅在应用层维护——**不加数据库级外键约束**
  （MyBatis-Plus 常见做法，简化词库导入与逻辑删除），
  但所有关联列必须建索引。

---

## 常见错误

- Controller 直接返回实体——必须映射为 DTO；实体包含内部字段
  （密码哈希、`deleted` 标志）。
- 错题本/单词列表里的 N+1 查询——用 `selectBatchIds` 或联查，
  不要在循环里逐个查单词。
- 手写 XML 时忘记逻辑删除条件——XML 查询不会自动附加 `deleted = 0`，
  必须手动加上。
