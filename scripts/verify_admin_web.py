#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
管理端前端静态检查（不需要后端、不需要浏览器）。

覆盖肉眼难以持续守住的约束：
  1. 分层：services/ 之外不得出现裸请求（fetch / axios / http.）
  2. 类型安全：无 any、无 @ts-ignore、无 as 强转（唯一豁免 utils/request.ts）
  3. Table 必须指定 rowKey（防 index key 导致操作列串行）
  4. types/ 里的 API 类型名与后端 DTO 对应
  5. package.json 依赖白名单（防悄悄引入 UI 框架/状态库）
  6. 敏感信息未硬编码（token/password 不应出现在 src/）
  7. 必做交互约束：1007 不清 token、非 ADMIN 不落 token、ADMIN 行禁用按钮

用法：
  PYTHONIOENCODING=utf-8 python scripts/verify_admin_web.py
"""
import io
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "frontend"))
SRC = os.path.join(ROOT, "src")

passed = 0
failed = 0
failures = []


def check(label, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print("  [OK] %s" % label)
    else:
        failed += 1
        failures.append("%s%s" % (label, (" | " + detail) if detail else ""))
        print("  [FAIL] %s%s" % (label, (" | " + detail) if detail else ""))


def read(path):
    return io.open(path, encoding="utf-8").read()


def walk_files(root, exts):
    out = []
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in filenames:
            if any(name.endswith(e) for e in exts):
                out.append(os.path.join(dirpath, name))
    return sorted(out)


def strip_comments(text, is_tsx):
    """剥离注释。

    小程序的教训：门禁被注释里的文字误伤过。这里同样先剥注释再匹配。
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    # 行注释：只剥「行首或空白后」的 //，避免误伤 URL 里的 //
    text = re.sub(r"(?m)^\s*//.*$", "", text)
    text = re.sub(r"(?m)\s//[^\"']*$", "", text)
    return text


def strip_imports(text):
    """剥掉全部 import 语句。

    `as` 检测会把 `import { App as AntdApp }` 这类**别名语法**误判为类型强转，
    而 import 块常跨多行，逐行判断会漏。直接整段删除更稳。
    """
    return re.sub(r"(?m)^\s*import\b[\s\S]*?from\s+['\"][^'\"]+['\"]\s*;?\s*$", "", text)


def rel(path):
    return os.path.relpath(path, ROOT).replace("\\", "/")


# ----------------------------------------------------------------------
# 1. 分层：services 之外不得有裸请求
# ----------------------------------------------------------------------
def check_layering():
    print("\n[1] 分层：请求只出现在 services/（与 utils/request.ts）")
    offenders = []
    for path in walk_files(os.path.join(SRC, "pages"), [".ts", ".tsx"]) + \
            walk_files(os.path.join(SRC, "components"), [".ts", ".tsx"]) + \
            walk_files(os.path.join(SRC, "hooks"), [".ts", ".tsx"]):
        code = strip_comments(read(path), path.endswith(".tsx"))
        for pattern, why in (
            (r"\bfetch\s*\(", "fetch()"),
            (r"\baxios\b", "axios"),
            (r"['\"`]/api/", "硬编码 /api/ 路径"),
        ):
            if re.search(pattern, code):
                offenders.append("%s 里出现 %s" % (rel(path), why))
    check("页面/组件/hook 中无裸请求与硬编码接口路径",
          not offenders, "; ".join(offenders[:5]))

    # services 里必须全部通过 utils/request 导入
    svc_bad = []
    for path in walk_files(os.path.join(SRC, "services"), [".ts"]):
        code = strip_comments(read(path), False)
        if "from '../utils/request'" not in code:
            svc_bad.append(rel(path))
    check("每个 service 文件都通过 utils/request 发请求",
          not svc_bad, "未导入: " + ", ".join(svc_bad))

    # 各 service 不得直接判 code / 弹 UI
    svc_bad2 = []
    for path in walk_files(os.path.join(SRC, "services"), [".ts"]):
        code = strip_comments(read(path), False)
        if re.search(r"\.code\s*===", code):
            svc_bad2.append(rel(path) + " 判 code")
        if re.search(r"\bmessage\.", code):
            svc_bad2.append(rel(path) + " 弹 UI")
    check("services 不含业务判断与 UI 逻辑", not svc_bad2, "; ".join(svc_bad2[:5]))


# ----------------------------------------------------------------------
# 2. 类型安全
# ----------------------------------------------------------------------
def check_type_safety():
    print("\n[2] 类型安全")
    files = walk_files(SRC, [".ts", ".tsx"])
    any_bad = []
    ts_ignore_bad = []
    as_bad = []

    for path in files:
        name = rel(path)
        code = strip_comments(read(path), path.endswith(".tsx"))
        # any：`: any`、`<any>`、`any[]`、`as any`
        for m in re.finditer(r"(:\s*any\b|<any>|\bany\[\]|as\s+any\b)", code):
            # 注释里出现的不算（已剥注释）
            line = code[: m.start()].count("\n") + 1
            any_bad.append("%s:%d" % (name, line))
        if re.search(r"@ts-ignore|@ts-nocheck", code):
            ts_ignore_bad.append(name)
        # as 强转：唯一豁免 utils/request.ts 的 unwrap。
        # 先剥 import，否则 `import { X as Y }` 全被误判为强转。
        if name != "src/utils/request.ts":
            body = strip_imports(code)
            for m in re.finditer(r"\bas\s+(?!const\b)[A-Z]", body):
                as_bad.append("%s:%d" % (name, body[: m.start()].count("\n") + 1))

    check("无 any（: any / <any> / as any）", not any_bad,
          "命中: " + ", ".join(any_bad[:6]))
    check("无 @ts-ignore / @ts-nocheck", not ts_ignore_bad, ", ".join(ts_ignore_bad))
    check("无 as 强转（豁免 utils/request.ts 的 unwrap）", not as_bad,
          "命中: " + ", ".join(as_bad[:6]))

    # 后端 code 是字符串，前端不得用数字比较
    strict_zero_bad = []
    for path in files:
        code = strip_comments(read(path), path.endswith(".tsx"))
        if re.search(r"code\s*===?\s*0\b", code):
            strict_zero_bad.append(rel(path))
    check("code 用字符串比较（不得 === 0）", not strict_zero_bad,
          ", ".join(strict_zero_bad))


# ----------------------------------------------------------------------
# 3. Table rowKey
# ----------------------------------------------------------------------
def check_table_rowkey():
    print("\n[3] Table 必须指定 rowKey")
    bad = []
    tables = 0
    for path in walk_files(SRC, [".tsx"]):
        code = strip_comments(read(path), True)
        for m in re.finditer(r"<Table[<A-Za-z0-9_]*", code):
            tables += 1
            seg = code[m.start(): m.start() + 900]
            if "rowKey" not in seg:
                bad.append("%s（第 %d 个 Table）" % (rel(path), tables))
    check("所有 <Table> 都带 rowKey（防 index key）", not bad,
          "; ".join(bad[:5]))
    check("项目里确实有 Table（检查未空跑）", tables >= 3, "找到 %d 个" % tables)


# ----------------------------------------------------------------------
# 4. 类型与后端 DTO 对应
# ----------------------------------------------------------------------
def check_types_match_backend():
    print("\n[4] 类型定义与后端 DTO 对应")

    def has_fields(file_rel, fields, label):
        path = os.path.join(SRC, file_rel)
        if not os.path.exists(path):
            check(label, False, "文件不存在: %s" % file_rel)
            return
        code = read(path)
        missing = [f for f in fields if not re.search(r"\b%s\s*[?:]" % f, code)]
        check(label, not missing, "缺字段: " + ", ".join(missing))

    has_fields("types/common.ts", ["code", "message", "data"], "Result<T> 含 code/message/data")
    has_fields("types/common.ts", ["list", "total", "page", "size"], "PageResult<T> 含 list/total/page/size")
    has_fields("types/book.ts",
               ["bookId", "name", "category", "wordCount", "description", "createTime"],
               "AdminBook 字段与 /api/admin/books 一致")
    has_fields("types/word.ts",
               ["wordId", "word", "phonetic", "translation", "example"],
               "Word 字段与 /api/admin/words 一致")
    has_fields("types/user.ts",
               ["userId", "username", "nickname", "role", "status",
                "totalCheckInDays", "createTime"],
               "AdminUser 字段与 /api/admin/users 一致")
    has_fields("types/monitor.ts",
               ["totalCalls", "successRate", "degradedCount", "registeredUsers",
                "features", "recentEvents"],
               "AiStats 字段与 /api/admin/monitor/ai-stats 一致")
    has_fields("types/monitor.ts",
               ["feature", "total", "success", "degraded", "timeout", "error", "avgCostMs"],
               "AiFeatureStat 字段一致")

    # Result.data 必须允许 null
    code = read(os.path.join(SRC, "types/common.ts"))
    check("Result.data 允许 null（实测失败响应 data 为 null）",
          re.search(r"data\s*:\s*T\s*\|\s*null", code) is not None)

    # 角色用联合字面量
    auth = read(os.path.join(SRC, "types/auth.ts"))
    check("UserRole 是联合字面量（非 string）",
          re.search(r"UserRole\s*=\s*'USER'\s*\|\s*'ADMIN'", auth) is not None)


# ----------------------------------------------------------------------
# 5. 依赖白名单
# ----------------------------------------------------------------------
ALLOWED_DEPS = {"react", "react-dom", "antd", "@ant-design/icons",
                "react-router-dom", "axios"}


def check_dependencies():
    print("\n[5] 依赖白名单")
    pkg = json.loads(read(os.path.join(ROOT, "package.json")))
    deps = set(pkg.get("dependencies", {}).keys())
    extra = deps - ALLOWED_DEPS
    check("无白名单外依赖（防悄悄引入状态库/UI 框架）", not extra,
          "多余: " + ", ".join(sorted(extra)))
    required = {"react", "react-dom", "antd", "react-router-dom", "axios"}
    check("必需依赖齐全", required <= deps,
          "缺: " + ", ".join(sorted(required - deps)))

    scripts = pkg.get("scripts", {})
    check("build 脚本包含 tsc（类型必须真的被检查）",
          "tsc" in scripts.get("build", ""), scripts.get("build", ""))


# ----------------------------------------------------------------------
# 6. 敏感信息
# ----------------------------------------------------------------------
def check_no_secrets():
    print("\n[6] 源码中无硬编码凭据")
    bad = []
    for path in walk_files(SRC, [".ts", ".tsx"]):
        code = strip_comments(read(path), path.endswith(".tsx"))
        if re.search(r"Admin@12345|password\s*[:=]\s*['\"][^'\"]{4,}['\"]", code):
            # 表单 initialValues 里的空串不算
            bad.append(rel(path))
    check("src/ 中无硬编码账号密码", not bad, ", ".join(bad))


# ----------------------------------------------------------------------
# 7. 关键交互约束（这些是实测过、错过一次就会踩的）
# ----------------------------------------------------------------------
def check_critical_behaviors():
    print("\n[7] 关键交互约束")
    req = read(os.path.join(SRC, "utils", "request.ts"))

    check("1007 分支存在（403 无权限）", "'1007'" in req)
    # 1007 分支内不得 clearAuth
    m = re.search(r"if\s*\(code\s*===\s*'1007'\)\s*\{(.*?)\}", req, re.S)
    check("1007 分支不清 token（登录态是好的）",
          m is not None and "clearAuth" not in m.group(1),
          "该分支出现 clearAuth" if m else "未找到 1007 分支")

    m2 = re.search(r"if\s*\(code\s*===\s*'1006'\s*\|\|\s*code\s*===\s*'1003'\)\s*\{(.*?)\}",
                   req, re.S)
    check("1006/1003 分支清 token", m2 is not None and "clearAuth" in m2.group(1))

    # 必须检查**错误回调内部**，而不是全文计数：
    # 全文计数会在「成功分支有条、错误分支被删」时误通过（真踩过）。
    err_cb = re.search(
        r"\(error:\s*AxiosError[^)]*\)\s*=>\s*\{(.*?)\n  \},\n\);", req, re.S)
    check("发现 axios 错误回调（门禁未空跑）", err_cb is not None)
    if err_cb:
        check("错误回调内也做 code 分流（否则 401/403 不跳登录）",
              "handleBusinessCode" in err_cb.group(1),
              "错误回调里没有 handleBusinessCode")

    check("multipart 未手工设 Content-Type",
          "multipart/form-data'" not in req and '"multipart/form-data"' not in req)

    login = read(os.path.join(SRC, "pages", "Login", "index.tsx"))
    check("登录页判 role，非 ADMIN 不落 token",
          "role !== 'ADMIN'" in login and "return;" in login)

    usermgr = read(os.path.join(SRC, "pages", "UserMgr", "index.tsx"))
    check("ADMIN 行禁用按钮 disabled", re.search(r"role === 'ADMIN'[\s\S]{0,400}disabled",
                                                usermgr) is not None)

    wordmodal = read(os.path.join(SRC, "pages", "WordBank", "WordModal.tsx"))
    check("单词写操作后回调父页刷新（wordCount 会变）",
          "onChanged()" in wordmodal)

    # 搜索必须重置页码
    for name, path in (("用户管理", os.path.join(SRC, "pages", "UserMgr", "index.tsx")),
                       ("单词弹窗", os.path.join(SRC, "pages", "WordBank", "WordModal.tsx"))):
        code = read(path)
        check("%s搜索后重置页码（否则停在高页码看到空表）" % name,
              re.search(r"setPage\(1\)", code) is not None)

    importmodal = read(os.path.join(SRC, "components", "JsonlImportModal.tsx"))
    # 字段名以字符串字面量出现在 str(node, 'uk') / str(item, 'translation') 等调用里
    check("导入预览的字段映射与后端一致（uk/translations/sentences）",
          all(k in importmodal for k in ("'uk'", "translations", "sentences")))
    check("预览只认 word 必填（与后端 parseLine 一致）",
          re.search(r"if\s*\(!word\)\s*return null", importmodal) is not None)
    check("导入结果展示成功/跳过计数",
          "result.success" in importmodal and "result.skipped" in importmodal)

    monitor = read(os.path.join(SRC, "pages", "Monitor", "index.tsx"))
    check("监控页注明「服务启动以来累计、重启清零」",
          "重启" in monitor and "累计" in monitor)
    check("successRate 不乘 100（后端已是百分数）",
          "successRate * 100" not in monitor and "successRate*100" not in monitor)

    # useSyncExternalStore 的 getSnapshot 必须返回**引用稳定**的值。
    # 真实踩过：直接返回 getUserInfo() 每次都产生新对象 → React 判定快照一直变
    # → 无限重渲染（Maximum update depth exceeded，整页白屏）。
    auth_hook = read(os.path.join(SRC, "hooks", "useAuth.ts"))
    # 取**全部** useSyncExternalStore 调用的快照函数名，逐个检查——
    # 只取第一个会漏掉第二处（token 用的是 getToken，安全；user 用过 getToken 的
    # 名字就不对，但 position 不同）。
    snapshots = re.findall(r"useSyncExternalStore\(\s*subscribe\s*,\s*([A-Za-z_]+)", auth_hook)
    check("发现 useSyncExternalStore 用法（门禁未空跑）", len(snapshots) >= 1,
          "找到 %d 处" % len(snapshots))
    bad_snap = [s for s in snapshots if s in ("getUserInfo", "getUser")]
    check("useSyncExternalStore 的快照不是「每次新对象」的 getter",
          not bad_snap, "危险快照: %s" % ", ".join(bad_snap))
    check("useAuth 用字符串快照 + useMemo 解析（防无限重渲染）",
          "getUserRaw" in auth_hook and "useMemo" in auth_hook)

    fmt = strip_comments(read(os.path.join(SRC, "utils", "format.ts")), False)
    check("createTime 原样展示，不 new Date（Safari 会 Invalid Date）",
          "new Date" not in fmt)


# ----------------------------------------------------------------------
# 8. 目录结构完整性
# ----------------------------------------------------------------------
def check_structure():
    print("\n[8] 目录结构")
    expected = [
        "src/main.tsx",
        "src/App.tsx",
        "src/utils/request.ts",
        "src/utils/auth.ts",
        "src/utils/format.ts",
        "src/services/auth.ts",
        "src/services/book.ts",
        "src/services/word.ts",
        "src/services/user.ts",
        "src/services/monitor.ts",
        "src/components/AdminLayout.tsx",
        "src/components/RequireAdmin.tsx",
        "src/pages/Login/index.tsx",
        "src/pages/WordBank/index.tsx",
        "src/pages/WordBank/WordModal.tsx",
        "src/pages/UserMgr/index.tsx",
        "src/pages/Monitor/index.tsx",
    ]
    missing = [p for p in expected if not os.path.exists(os.path.join(ROOT, p))]
    check("关键文件齐全", not missing, "缺: " + ", ".join(missing))

    app = read(os.path.join(SRC, "App.tsx"))
    for route in ("/login", "wordbank", "users", "monitor"):
        check("路由注册 %s" % route, route in app)


def main() -> int:
    print("=" * 72)
    print("管理端前端静态检查")
    print("=" * 72)

    if not os.path.isdir(SRC):
        print("✗ 找不到 frontend/src，请先创建工程")
        return 1

    check_layering()
    check_type_safety()
    check_table_rowkey()
    check_types_match_backend()
    check_dependencies()
    check_no_secrets()
    check_critical_behaviors()
    check_structure()

    print("\n" + "=" * 72)
    if failed:
        print("✗ %d 项通过，%d 项失败" % (passed, failed))
        print("\n失败项：")
        for f in failures:
            print("  - " + f)
        return 1
    print("✓ %d 项静态检查全部通过" % passed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
