#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
实体 ↔ 真实 MySQL 表结构 交叉核对（阶段 4 闸口验证）。

为什么需要本脚本（而不只依赖 JUnit 测试）：
    `mvn test` 用的是 H2 内存库 + 手写的 `schema-h2.sql`（V001/V002 的等价翻译）。
    H2 结构由人手抄写，一旦抄错（漏列、类型不符、可空性不符），
    JUnit 会在「与生产不一致的假象」下通过。
    本脚本**独立地**（不经过 MyBatis / Spring）直接读 Java 源码 +
    直连真实 MySQL 的 INFORMATION_SCHEMA，做双向列名核对，
    专门用来抓「H2 手抄稿与 V001/V002 真实 DDL 漂移」。

检查项：
    1. 每个实体的 @TableName 指向的表在真实库中存在。
    2. 实体每个字段（camelCase → snake_case）在真实表中都有对应列；
       反之真实表的每个列都能映射回实体字段（双向核对，抓漏字段）。
    3. 所有「NOT NULL 且无默认值且非自增」的列，必须有实体字段承载
       （否则插入必然报 1364）。
    4. create_time / update_time 必须在实体上标注 @TableField(fill=...)，
       否则因 DB 层无默认值而插入失败（阶段 3 实测报错 1364）。
    5. 逻辑删除列 deleted：实体须标 @TableLogic；
       真实表有 deleted 列时实体必须有（user_word_book 为物理删除，反向也要成立）。

用法：
    python backend/scripts/verify-schema-entities.py
    # 可用环境变量覆盖连接参数
    DB_HOST=127.0.0.1 DB_PORT=3306 DB_USERNAME=root DB_PASSWORD=... DB_NAME=ai_vocab

退出码：0 = 全部通过；1 = 存在不一致（输出差异明细）。
"""

import os
import re
import sys

try:
    import pymysql
except ImportError:
    sys.stderr.write("缺少依赖 pymysql，请先执行：pip install pymysql\n")
    sys.exit(2)

# ----------------------------------------------------------------------
# 配置
# ----------------------------------------------------------------------
HOST = os.environ.get("DB_HOST", "127.0.0.1")
PORT = int(os.environ.get("DB_PORT", "3306"))
USER = os.environ.get("DB_USERNAME", "root")
# 密码一律从环境变量读取，不写默认值（规范红线：禁硬编码凭证）。
# 与 backend 的 application-local.yml 保持一致：DB_PASSWORD=... python ...
PASSWORD = os.environ.get("DB_PASSWORD", "")
DATABASE = os.environ.get("DB_NAME", "ai_vocab")

HERE = os.path.dirname(os.path.abspath(__file__))
ENTITY_DIR = os.path.normpath(os.path.join(HERE, "..", "src", "main", "java", "com", "vocab", "entity"))

# 时间戳列：必须由 MetaObjectHandler 填充（DB 层无默认值）
TIMESTAMP_COLUMNS = {"create_time": "INSERT", "update_time": "INSERT_UPDATE"}


def camel_to_snake(name: str) -> str:
    """camelCase / PascalCase → snake_case（与 MyBatis-Plus 默认映射一致）。"""
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def parse_entity(path: str) -> dict:
    """解析一个实体源码，抽出表名、字段列表、@TableField/@TableLogic/@TableId 信息。

    刻意用正则而不是引入 Java 解析器：本文件的目的是**独立复核**，
    保持简单直白、无额外依赖比「解析完备」更重要。
    """
    with open(path, encoding="utf-8") as fh:
        src = fh.read()

    table_match = re.search(r'@TableName\(\s*"([^"]+)"\s*\)', src)
    if not table_match:
        raise ValueError(f"{path}: 未找到 @TableName")
    table = table_match.group(1)

    lines = src.splitlines()
    fields = []
    pending_fill = None       # @TableField(fill = ...) 的取值
    pending_table_field = None  # @TableField(value = "...")
    pending_logic = False
    pending_id = False

    field_re = re.compile(r"^\s*private\s+([\w<>\[\].]+)\s+(\w+)\s*;")
    for raw in lines:
        line = raw.strip()

        # 累积注解（注解与字段可能同行或分行）
        fill_match = re.search(r"FieldFill\.(\w+)", line)
        if fill_match and line.startswith("@TableField"):
            pending_fill = fill_match.group(1)
        elif fill_match:
            pending_fill = fill_match.group(1)

        m_field_value = re.search(r'@TableField\([^)]*value\s*=\s*"([^"]+)"', line)
        if m_field_value:
            pending_table_field = m_field_value.group(1)
        elif line.startswith("@TableField"):
            pending_table_field = None

        if line.startswith("@TableLogic"):
            pending_logic = True
        if line.startswith("@TableId"):
            pending_id = True

        m = field_re.match(raw)
        if not m:
            continue
        java_type, java_name = m.group(1), m.group(2)
        if java_name == "serialVersionUID":
            continue

        column = pending_table_field or camel_to_snake(java_name)
        fields.append({
            "java_name": java_name,
            "java_type": java_type,
            "column": column,
            "fill": pending_fill,
            "logic_delete": pending_logic,
            "is_id": pending_id,
        })
        pending_fill = None
        pending_table_field = None
        pending_logic = False
        pending_id = False

    return {"path": os.path.basename(path), "table": table, "fields": fields}


def load_real_columns(cursor, table: str) -> dict:
    """读真实表的列信息：列名 → (类型, 是否可空, 默认值, 是否自增)。"""
    cursor.execute(
        """
        SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s
        ORDER BY ORDINAL_POSITION
        """,
        (DATABASE, table),
    )
    result = {}
    for name, col_type, nullable, default, extra in cursor.fetchall():
        result[name] = {
            "type": col_type,
            "nullable": nullable == "YES",
            "default": default,
            "auto_increment": "auto_increment" in (extra or "").lower(),
        }
    return result


def main() -> int:
    entity_files = sorted(
        f for f in os.listdir(ENTITY_DIR) if f.endswith(".java")
    )
    if not entity_files:
        sys.stderr.write(f"未在 {ENTITY_DIR} 找到实体文件\n")
        return 1

    entities = [parse_entity(os.path.join(ENTITY_DIR, f)) for f in entity_files]

    if not PASSWORD:
        sys.stderr.write(
            "未提供数据库密码。请用环境变量传入（勿写入脚本）：\n"
            "  DB_PASSWORD=*** python backend/scripts/verify-schema-entities.py\n"
        )
        return 2

    conn = pymysql.connect(
        host=HOST, port=PORT, user=USER, password=PASSWORD,
        database=DATABASE, charset="utf8mb4", autocommit=True,
    )
    problems = []
    checked_columns = 0

    try:
        with conn.cursor() as cursor:
            for entity in entities:
                table = entity["table"]
                real = load_real_columns(cursor, table)
                if not real:
                    problems.append(f"[表缺失] {entity['path']} → 表 `{table}` 在库中不存在")
                    continue

                print(f"=== {table}  (实体 {entity['path']}) ===")
                print(f"    真实列 {len(real)} 个 / 实体字段 {len(entity['fields'])} 个")

                entity_columns = {f["column"] for f in entity["fields"]}

                # 检查 2a：实体字段 → 真实列
                missing_on_db = sorted(entity_columns - set(real))
                if missing_on_db:
                    problems.append(
                        f"[实体有、库无] {table}: {missing_on_db}"
                    )

                # 检查 2b：真实列 → 实体字段（抓漏字段）
                missing_on_entity = sorted(set(real) - entity_columns)
                if missing_on_entity:
                    problems.append(
                        f"[库有、实体无] {table}: {missing_on_entity}"
                    )

                # 检查 3：NOT NULL 无默认值非自增的列，必须有实体字段承载
                for col, meta in real.items():
                    if not meta["nullable"] and meta["default"] is None \
                            and not meta["auto_increment"]:
                        if col not in entity_columns:
                            problems.append(
                                f"[写入必失败] {table}.{col} 是 NOT NULL 且无默认值，"
                                f"但实体无对应字段 → 插入会报 1364"
                            )

                # 检查 4：时间戳列必须有 fill 标注
                by_column = {f["column"]: f for f in entity["fields"]}
                for col, expected_fill in TIMESTAMP_COLUMNS.items():
                    if col not in real:
                        continue
                    field = by_column.get(col)
                    if field is None:
                        problems.append(f"[时间戳缺失] {table}.{col} 无实体字段")
                        continue
                    if field["fill"] != expected_fill:
                        problems.append(
                            f"[fill 标注错误] {table}.{col} 期望 FieldFill.{expected_fill}，"
                            f"实际 {field['fill']} → 因 DB 无默认值，插入会报 1364"
                        )

                # 检查 5：deleted 列 ↔ @TableLogic 必须一致
                has_deleted_col = "deleted" in real
                field = by_column.get("deleted")
                if has_deleted_col:
                    if field is None:
                        problems.append(f"[逻辑删除缺失] {table} 有 deleted 列，但实体无字段")
                    elif not field["logic_delete"]:
                        problems.append(f"[@TableLogic 缺失] {table}.deleted 未标注 @TableLogic")
                else:
                    if field is not None:
                        problems.append(
                            f"[多余 deleted] {table} 表无 deleted 列（物理删除），"
                            f"但实体有 deleted 字段"
                        )

                checked_columns += len(real)
                print(f"    时间戳 fill: "
                      f"create_time={by_column.get('create_time', {}).get('fill')}  "
                      f"update_time={by_column.get('update_time', {}).get('fill')}  "
                      f"deleted_col={'Y' if has_deleted_col else 'N'}  "
                      f"@TableLogic={'Y' if (field and field['logic_delete']) else 'N'}")
    finally:
        conn.close()

    print()
    print("-" * 72)
    print(f"实体 {len(entities)} 个 / 核对列 {checked_columns} 个")
    if problems:
        print(f"发现 {len(problems)} 处不一致：")
        for p in problems:
            print("  ✗ " + p)
        return 1

    print("✓ 全部通过：实体字段与真实 MySQL 表结构双向一致，时间戳 fill 标注齐全。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
