#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
H2 测试结构 ↔ 真实 DDL 一致性核对（阶段 4 闸口验证）。

背景：
    `src/test/resources/schema-h2.sql` 是 `src/main/resources/db/V001__init.sql`
    + `V002__word_progress.sql` 的**手写等价翻译**（因为 H2 无法解析 MySQL 的
    ENGINE / CHARSET / COLLATE 语法）。

    手写翻译意味着「MySQL 改了 DDL、H2 忘了同步」的风险：
    此时 `mvn test` 仍会全绿，却在验证一个**与生产不同的结构**。
    本脚本把这种漂移变成一次失败。

检查项（逐表逐列）：
    1. 表集合一致（V001+V002 的表 ↔ schema-h2.sql 的表）。
    2. 列集合一致。
    3. 列的数据类型一致（归一化后比较；H2 的 TINYINT/INT/BIGINT/DATE/DATETIME
       /VARCHAR(n) 与 MySQL 同名类型应可逐字对应）。
    4. 可空性一致（NOT NULL 是我方显式声明的，必须一致）。
    5. 唯一约束/索引的列组合一致。

用法：
    python backend/scripts/verify-h2-schema-parity.py
退出码：0 = 一致；1 = 存在漂移。
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND = os.path.normpath(os.path.join(HERE, ".."))
REAL_FILES = [
    os.path.join(BACKEND, "src", "main", "resources", "db", "V001__init.sql"),
    os.path.join(BACKEND, "src", "main", "resources", "db", "V002__word_progress.sql"),
]
H2_FILE = os.path.join(BACKEND, "src", "test", "resources", "schema-h2.sql")

COLUMN_DEF = re.compile(
    r"^\s*`(?P<name>\w+)`\s+(?P<type>\w+(?:\s*\(\s*\d+\s*(?:,\s*\d+\s*)?\))?)"
    r"(?P<rest>[^,]*)",
    re.IGNORECASE,
)


def strip_comments(sql: str) -> str:
    """去掉 -- 行注释（避免注释里的括号/逗号干扰解析）。"""
    out = []
    for line in sql.splitlines():
        idx = line.find("--")
        out.append(line[:idx] if idx >= 0 else line)
    return "\n".join(out)


def normalize_type(raw: str) -> str:
    """归一化类型写法：去空格、统一小写、去 Display Width。"""
    t = raw.strip().lower()
    t = re.sub(r"\s+", "", t)
    t = re.sub(r"\(\s*(\d+)\s*\)", r"(\1)", t)
    return t


def parse_tables(sql: str) -> dict:
    """解析 CREATE TABLE 块 → {table: {columns: {name: (type, not_null)}, uniques: [...]}}。"""
    sql = strip_comments(sql)
    tables = {}

    for m in re.finditer(
        r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`(?P<name>\w+)`\s*\((?P<body>.*?)\n\)\s*(?P<tail>[^;]*)",
        sql,
        re.IGNORECASE | re.DOTALL,
    ):
        table = m.group("name")
        body = m.group("body")
        columns = {}
        uniques = []

        # 逐行解析：列定义 / 约束
        for raw_line in body.splitlines():
            line = raw_line.strip().rstrip(",").strip()
            if not line:
                continue

            if re.match(r"^(UNIQUE\s+)?KEY\s", line, re.IGNORECASE) or \
               re.match(r"^(CONSTRAINT|UNIQUE)\s", line, re.IGNORECASE) or \
               re.match(r"^(PRIMARY\s+KEY|INDEX)\s", line, re.IGNORECASE):
                # 统一取括号内的列组合
                cm = re.search(r"\((.*?)\)", line, re.DOTALL)
                if cm:
                    cols = tuple(sorted(
                        c.strip().strip("`").lower()
                        for c in cm.group(1).split(",")
                        if c.strip()
                    ))
                    is_unique = bool(re.search(r"\bUNIQUE\b", line, re.IGNORECASE))
                    is_primary = bool(re.search(r"PRIMARY\s+KEY", line, re.IGNORECASE))
                    if is_unique or is_primary:
                        uniques.append(cols)
                continue

            cm = COLUMN_DEF.match(raw_line)
            if cm:
                name = cm.group("name").lower()
                col_type = normalize_type(cm.group("type"))
                rest = cm.group("rest").upper()
                not_null = "NOT NULL" in rest
                columns[name] = (col_type, not_null)
                continue

            # CREATE INDEX 形式在主循环外处理
        if columns:
            tables[table.lower()] = {"columns": columns, "uniques": sorted(uniques)}

    # CREATE [UNIQUE] INDEX IF NOT EXISTS `x` ON `t` (`a`, `b`)
    # 注意：只有 UNIQUE INDEX 才算唯一约束；普通 INDEX 只是查询索引，
    # 不能混入 uniques（否则会把「查询索引」误报为「唯一约束漂移」）。
    for m in re.finditer(
        r"CREATE\s+(?P<uniq>UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?`?\w+`?\s+ON\s+`(?P<name>\w+)`\s*\((?P<cols>[^)]*)\)",
        sql,
        re.IGNORECASE,
    ):
        if not m.group("uniq"):
            continue
        table = m.group("name").lower()
        cols = tuple(sorted(c.strip().strip("`").lower() for c in m.group("cols").split(",")))
        if table in tables:
            tables[table]["uniques"] = sorted(set(tables[table]["uniques"]) | {cols})

    return tables


def main() -> int:
    real_sql = "\n".join(open(f, encoding="utf-8").read() for f in REAL_FILES)
    h2_sql = open(H2_FILE, encoding="utf-8").read()

    real = parse_tables(real_sql)
    h2 = parse_tables(h2_sql)

    problems = []

    print(f"真实 DDL 表: {sorted(real)}")
    print(f"H2 结构表 : {sorted(h2)}")
    print()

    only_real = sorted(set(real) - set(h2))
    only_h2 = sorted(set(h2) - set(real))
    if only_real:
        problems.append(f"表只在真实 DDL 中：{only_real}")
    if only_h2:
        problems.append(f"表只在 H2 中：{only_h2}")

    for table in sorted(set(real) & set(h2)):
        rc, hc = real[table]["columns"], h2[table]["columns"]

        missing = sorted(set(rc) - set(hc))
        extra = sorted(set(hc) - set(rc))
        if missing:
            problems.append(f"{table}: H2 缺少列 {missing}")
        if extra:
            problems.append(f"{table}: H2 多出列 {extra}")

        for col in sorted(set(rc) & set(hc)):
            rt, rn = rc[col]
            ht, hn = hc[col]
            if rt != ht:
                problems.append(f"{table}.{col}: 类型不一致 真实={rt} H2={ht}")
            if rn != hn:
                problems.append(
                    f"{table}.{col}: 可空性不一致 真实 NOT_NULL={rn} H2 NOT_NULL={hn}"
                )

        if real[table]["uniques"] != h2[table]["uniques"]:
            problems.append(
                f"{table}: 唯一约束列组合不一致\n"
                f"        真实={real[table]['uniques']}\n"
                f"        H2  ={h2[table]['uniques']}"
            )

        print(f"  {table}: 列 {len(rc)}/{len(hc)}，唯一约束 {len(real[table]['uniques'])}/{len(h2[table]['uniques'])}")

    print()
    print("-" * 72)
    if problems:
        print(f"发现 {len(problems)} 处漂移（必须先同步 schema-h2.sql 再提交）：")
        for p in problems:
            print("  ✗ " + p)
        return 1

    print("✓ schema-h2.sql 与真实 DDL（V001+V002）结构一致：表/列/类型/可空性/唯一约束。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
