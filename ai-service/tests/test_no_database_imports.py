"""架构红线自动化校验：ai-service 不得引入任何数据库驱动、不得连 MySQL。

把「红线」变成可执行断言，而不是靠人肉 grep——本机是 GBK 终端，
``grep -c`` 统计中文会数错，人肉检查不可靠。

两道关卡：
1. **代码层**：AST 遍历 ``app/**/*.py``，禁止 import 任何数据库驱动；
2. **依赖层**：``requirements.txt`` 中不得出现数据库包名。
"""

from __future__ import annotations

import ast
import pathlib

APP_DIR = pathlib.Path(__file__).resolve().parent.parent / "app"
REQUIREMENTS = pathlib.Path(__file__).resolve().parent.parent / "requirements.txt"

# 模块名的前缀匹配集合（用前缀是为了同时覆盖 mysqlclient、mysql.connector 等）。
FORBIDDEN_MODULES = (
    "mysql",
    "pymysql",
    "MySQLdb",
    "psycopg",
    "psycopg2",
    "sqlalchemy",
    "sqlite3",
    "aiomysql",
    "asyncpg",
    "pymongo",
    "sqlmodel",
    "databases",
)

# requirements.txt 里禁止出现的包名（小写比较）。
FORBIDDEN_REQUIREMENTS = (
    "mysql",
    "pymysql",
    "psycopg",
    "sqlalchemy",
    "sqlite",
    "aiomysql",
    "asyncpg",
    "pymongo",
    "sqlmodel",
    "databases",
    "alembic",
)


def _iter_python_files() -> list[pathlib.Path]:
    return sorted(APP_DIR.rglob("*.py"))


def _imported_modules(source: str) -> set[str]:
    """收集一个文件里所有被 import 的顶层模块名。"""
    tree = ast.parse(source)
    modules: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                modules.add(alias.name)
        elif isinstance(node, ast.ImportFrom):
            if node.module:
                modules.add(node.module)
            # 相对 import（node.module is None）不需要检查：只可能指向本项目内部模块
    return modules


def test_no_database_imports_in_app():
    offenders: list[str] = []
    files = _iter_python_files()
    assert files, "未找到任何 app 模块，测试本身可能失效"

    for path in files:
        source = path.read_text(encoding="utf-8")
        for module in _imported_modules(source):
            root = module.split(".")[0]
            if root in FORBIDDEN_MODULES:
                offenders.append(f"{path.relative_to(APP_DIR.parent)}: {module}")

    assert not offenders, "检测到数据库驱动 import（违反架构红线）: " + ", ".join(offenders)


def _requirement_lines() -> list[str]:
    """取 requirements.txt 中真正的依赖行（剔除注释与空行）。

    必须剔除注释：本案的注释恰好会提到 "SQLAlchemy"（用来说明 langchain 1.x
    不再依赖它），若直接扫全文会把自己的解释文字误判成依赖。
    """
    lines: list[str] = []
    for raw in REQUIREMENTS.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            lines.append(line.lower())
    return lines


def test_no_database_dependency_in_requirements():
    lines = _requirement_lines()
    assert lines, "未解析到任何依赖行，测试本身可能失效"
    offenders = [
        line for line in lines if any(name in line for name in FORBIDDEN_REQUIREMENTS)
    ]
    assert not offenders, f"requirements.txt 含数据库相关包: {offenders}"


def test_required_llm_stack_is_pinned():
    """反向校验：确认关键依赖确实被锁定（防止有人「为了干净」把它们删掉）。"""
    lines = _requirement_lines()
    for package in ("langchain==", "langgraph==", "langchain-openai==", "fastapi=="):
        assert any(line.startswith(package) for line in lines), (
            f"requirements.txt 缺少锁版依赖: {package}"
        )
