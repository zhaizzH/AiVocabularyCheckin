"""
小程序静态检查。

这个脚本存在的理由：小程序没有 pytest 那样的单测框架，而「页面里裸写 wx.request」
「地址散落各处」「console.log 混进提交」这类问题靠人眼 review 一定会漏。
把它变成可执行的门禁，每阶段跑一次，比事后 review 可靠。

检查项分两类：
  结构类——文件齐不齐、app.json 注册对不对；
  禁令类——规范里明确禁止的写法有没有出现。

退出码：全部通过 0；有任一失败 1。
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PAGE_EXTS = (".js", ".json", ".wxml", ".wxss")

# 页面/组件里出现的裸网络调用（utils/request.js 是唯一允许的出口）
BARE_REQUEST_PATTERNS = [
    r"wx\.request\s*\(",
    r"\bfetch\s*\(",
    r"\baxios\b",
]

# 允许出现后端地址的唯一文件（相对 ROOT）
# 只允许 constants.js：它是 BASE_URL 的唯一定义处。
# request.js 只能通过 require 引入常量，不得自己出现字面量——
# 否则「改地址改一处」的承诺就破了。
ADDRESS_ALLOWED_FILE = "utils/constants.js"
ADDRESS_PATTERN = re.compile(r"127\.0\.0\.1|localhost|https?://")

# 第三方图表库（项目红线：零第三方库，热力图用 view、曲线用 canvas 手绘）
THIRD_PARTY_PATTERNS = [
    r"""require\(\s*['"](echarts|ucharts|u-charts|@antv|f2|d3)""",
    r"""from\s+['"](echarts|ucharts|u-charts|@antv|f2|d3)""",
]

# 后端字段白名单（services 层出现未登记字段时提示，防字段名写错）
KNOWN_FIELDS = {
    "wordId", "bookId", "mistakeId", "userId",
    "username", "password", "nickname", "avatar", "role", "token", "userInfo",
    # wx-login 的唯一入参：wx.login() 拿到的临时凭证（不是后端的 Result.code）
    "code",
    "isNewUser", "createTime", "totalCheckInDays",
    "name", "category", "wordCount", "description", "selected", "learnedCount",
    "date", "total", "finished", "checkedIn", "words", "isReview", "learned",
    "word", "phonetic", "translation", "example", "known", "addedToMistakes",
    "checkInDate", "streakDays", "todayLearned", "todayMistakes",
    "totalLearned", "todayReviewCount", "lastCheckInDate",
    "stage", "nextReviewDate", "wrongCount", "correct",
    "source", "mastered", "removed", "list", "page", "size",
    "exampleTranslation", "mnemonic", "degraded", "explanation", "advice",
    "report", "overview", "weakness", "suggestion", "generatedAt",
    "days", "level", "points", "learnedCount", "reviewCount",
    "mistakeCount", "masteryRate", "weeks",
}


class Result:
    def __init__(self) -> None:
        self.failures: list[str] = []
        self.checks: list[str] = []

    def ok(self, name: str) -> None:
        self.checks.append(name)

    def fail(self, name: str, detail: str) -> None:
        self.failures.append(f"{name}\n{detail}")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def iter_source_files(base: Path, exts=(".js",)):
    """
    遍历待检查的源码文件。

    **排除 scripts/**：验证脚本本身是工具，不是小程序运行时代码。
    它必须能独立硬编码后端地址（若也从被测的 constants.js 引入，就成了
    「用被测代码验证被测代码」——constants.js 里地址写错时脚本会跟着一起错，
    反而测不出来）。同理它也必须能用 console.log 输出结果。
    """
    for path in sorted(base.rglob("*")):
        if not path.is_file() or path.suffix not in exts:
            continue
        if "scripts" in path.relative_to(ROOT).parts:
            continue
        yield path


def check_app_json(res: Result) -> dict:
    app_json_path = ROOT / "app.json"
    if not app_json_path.exists():
        res.fail("app.json 存在", "miniprogram/app.json 不存在")
        return {}
    app = json.loads(read(app_json_path))

    # 1. 每个 page 四件套齐全
    missing = []
    for page in app.get("pages", []):
        base = ROOT / page
        for ext in PAGE_EXTS:
            candidate = Path(str(base) + ext)
            if not candidate.exists():
                missing.append(str(candidate.relative_to(ROOT)))
    if missing:
        res.fail("页面四件套齐全", "缺少文件:\n  " + "\n  ".join(missing))
    else:
        res.ok(f"页面四件套齐全（{len(app.get('pages', []))} 个页面 × 4 文件）")

    # 2. tabBar 恰好 6 项且路径都已注册
    tab_list = app.get("tabBar", {}).get("list", [])
    if len(tab_list) != 6:
        res.fail("tabBar 为 6 项", f"实际 {len(tab_list)} 项")
    else:
        res.ok("tabBar 为 6 项")

    registered = set(app.get("pages", []))
    unregistered = [t.get("pagePath") for t in tab_list if t.get("pagePath") not in registered]
    if unregistered:
        res.fail("tabBar 路径已在 pages 注册", "未注册: " + ", ".join(map(str, unregistered)))
    else:
        res.ok("tabBar 路径均已在 pages 注册")

    # 3. tabBar 不配 iconPath（一期零图片资源，缺图会导致编译失败）
    with_icon = [t.get("pagePath") for t in tab_list if t.get("iconPath") or t.get("selectedIconPath")]
    if with_icon:
        res.fail("tabBar 不依赖图片资源", "以下 tab 配了 iconPath 但项目无图片资源: " + ", ".join(map(str, with_icon)))
    else:
        res.ok("tabBar 不依赖图片资源（纯文字）")

    return app


def check_bare_requests(res: Result) -> None:
    """页面与组件不得直接发起网络请求。"""
    offenders = []
    for base in (ROOT / "pages", ROOT / "components"):
        if not base.exists():
            continue
        for path in iter_source_files(base):
            text = read(path)
            for pattern in BARE_REQUEST_PATTERNS:
                if re.search(pattern, text):
                    offenders.append(f"{path.relative_to(ROOT)}: {pattern}")
    if offenders:
        res.fail("页面/组件无裸网络调用", "\n  ".join(offenders))
    else:
        res.ok("页面/组件无裸网络调用")


def check_console_log(res: Result) -> None:
    offenders = []
    for path in iter_source_files(ROOT):
        if re.search(r"console\.(log|debug|info)\s*\(", read(path)):
            offenders.append(str(path.relative_to(ROOT)))
    if offenders:
        res.fail("无 console.log/debug/info", "\n  ".join(offenders))
    else:
        res.ok("无 console.log/debug/info")


def check_hardcoded_address(res: Result) -> None:
    offenders = []
    for path in iter_source_files(ROOT):
        rel = path.relative_to(ROOT).as_posix()
        if rel == ADDRESS_ALLOWED_FILE:
            continue
        for lineno, line in enumerate(read(path).splitlines(), 1):
            if ADDRESS_PATTERN.search(line):
                offenders.append(f"{rel}:{lineno}: {line.strip()[:80]}")
    if offenders:
        res.fail(
            "后端地址仅存在于 utils/constants.js",
            "\n  ".join(offenders),
        )
    else:
        res.ok("后端地址仅存在于 utils/constants.js")


def check_third_party(res: Result) -> None:
    offenders = []
    for path in iter_source_files(ROOT):
        text = read(path)
        for pattern in THIRD_PARTY_PATTERNS:
            if re.search(pattern, text):
                offenders.append(f"{path.relative_to(ROOT)}: {pattern}")
    if (ROOT / "miniprogram_npm").exists():
        offenders.append("miniprogram_npm/ 目录存在（不应有 npm 构建产物）")
    if offenders:
        res.fail("零第三方库", "\n  ".join(offenders))
    else:
        res.ok("零第三方库（无图表库、无 miniprogram_npm）")


def check_service_fields(res: Result) -> None:
    """
    services 层内联 data 对象里的字段名必须在后端契约白名单内（防拼写错误）。

    只扫 `data: { ... }` 这种内联字面量，不扫全文件的 `key:` 模式——
    后者会把 request({url, method, timeout, auth}) 这类**本地调用选项**
    也当成后端字段，产生大量假通过/假失败。
    大多数 service 直接写 `data: data`（透传页面对象），那种情况类型责任在页面，
    这里只兜住「在 service 里就地构造请求体」的写法。
    """
    services = ROOT / "services"
    if not services.exists():
        res.ok("services 字段白名单（无 services 目录，跳过）")
        return

    unknown: list[str] = []
    inline_data_re = re.compile(r"\bdata\s*:\s*\{([^}]*)\}")
    field_re = re.compile(r"\b([a-z][a-zA-Z0-9]*)\s*:")
    for path in iter_source_files(services):
        text = read(path)
        for match in inline_data_re.finditer(text):
            body = match.group(1)
            lineno = text[: match.start()].count("\n") + 1
            for name in field_re.findall(body):
                if name not in KNOWN_FIELDS:
                    unknown.append(f"{path.relative_to(ROOT)}:{lineno}: {name}")
    if unknown:
        res.fail("services 内联请求体字段名在契约白名单内", "\n  ".join(unknown))
    else:
        res.ok("services 内联请求体字段名在契约白名单内")


def strip_js_comments(text: str) -> str:
    """去掉 // 行注释与 /* */ 块注释，保留代码。

    静态门禁匹配的是「代码里写了什么」，注释里为了说明反例而出现
    错误写法是正常的，不能被判成违规。
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    lines = []
    for line in text.splitlines():
        # 只处理不在字符串里的 //：本仓库的 JS 注释都在行首或代码后，
        # 且不会在字符串里出现 "//" 后跟空格的情况，简单切分已足够。
        idx = line.find("//")
        if idx >= 0:
            line = line[:idx]
        lines.append(line)
    return "\n".join(lines)


def check_stats_empty_logic(res: Result) -> None:
    """
    统计页空态不得用 masteryRate 判断。

    后端在「无任何错题」时 masteryRate 返回 100（表示没有薄弱点），
    若前端用 masteryRate === 0 判空态，空账号会被当成「全部掌握」而画出满格曲线。
    """
    stats_js = ROOT / "pages" / "stats" / "stats.js"
    if not stats_js.exists():
        res.ok("统计空态判据（页面未实现，跳过）")
        return
    # 先剥注释：本文件的注释里就会提到这个错误写法，
    # 若直接扫全文，解释性注释反而会让检查失败。
    code = strip_js_comments(read(stats_js))
    if re.search(r"masteryRate\s*===?\s*0", code):
        res.fail(
            "统计空态不使用 masteryRate === 0",
            "pages/stats/stats.js 出现 masteryRate === 0；"
            "无数据时后端返回 100，应改用 learnedCount+reviewCount+mistakeCount 全 0 判断",
        )
    else:
        res.ok("统计空态不使用 masteryRate === 0")


def check_about_license(res: Result) -> None:
    """
    关于页必须保留版本号与词库版权声明（PRD F10）。

    词库来自 KyleBing/english-vocabulary，BSD 3-Clause 要求
    在分发物中保留版权声明与免责声明。这行文案很容易在某次
    「精简界面」时被顺手删掉，而删掉就是许可证违规，肉眼看 diff
    也未必能发现，所以固化成门禁。
    """
    about_wxml = ROOT / "pages" / "about" / "about.wxml"
    if not about_wxml.exists():
        res.ok("关于页版权声明（页面未实现，跳过）")
        return
    text = read(about_wxml)

    missing = []
    if "version" not in text:
        missing.append("版本号占位 {{version}}")
    if "KyleBing" not in text:
        missing.append("词库项目名 KyleBing/english-vocabulary")
    if "BSD" not in text:
        missing.append("许可证名称 BSD 3-Clause")
    if "COPYRIGHT HOLDERS" not in text.upper():
        missing.append("BSD 免责声明原文")

    if missing:
        res.fail(
            "关于页含版本号与 BSD 3-Clause 词库版权",
            "pages/about/about.wxml 缺少：" + "、".join(missing),
        )
    else:
        res.ok("关于页含版本号与 BSD 3-Clause 词库版权")


def check_checkin_text(res: Result) -> None:
    """
    打卡文案不得写死日词数 20。

    total = 待复习词 + 每本已选词书各 20 新词 + 今日已上报词，
    多词书时 total > 20，写死会显示「25/20」。
    """
    study_js = ROOT / "pages" / "study" / "study.js"
    if not study_js.exists():
        res.ok("打卡文案不写死 20（页面未实现，跳过）")
        return
    text = read(study_js)
    # 匹配 "/20" 或 "÷20" 这类把 20 当分母的写法
    if re.search(r"(/|\u00f7)\s*20\b", text):
        res.fail(
            "打卡文案不写死 20",
            "pages/study/study.js 出现以 20 为分母的文案；"
            "应使用 plan.finished / plan.total（多词书时 total > 20）",
        )
    else:
        res.ok("打卡文案不写死 20")


def check_error_code_refs(res: Result) -> None:
    """
    ERROR_CODES.X 引用的名字必须真实定义在 constants.js。

    真实踩过的坑：pages/review/review.js 写了 ERROR_CODES.PARAM_INVALID，
    而常量表里叫 VALIDATION_FAILED。JS 对未定义属性返回 undefined，
    分支永远不命中且不报错——「无学习记录的词跳过」的兜底逻辑静默失效，
    那个词会永远卡在待复习列表里让用户无法推进。
    这类缺陷没有运行时报错，只能靠静态检查抓。
    """
    consts = ROOT / "utils" / "constants.js"
    if not consts.exists():
        res.ok("ERROR_CODES 引用有效（constants.js 缺失，跳过）")
        return
    src = read(consts)
    defined = set(re.findall(r"^\s{2}([A-Z][A-Z0-9_]+):", src, re.M))
    if not defined:
        res.fail("ERROR_CODES 引用有效", "没能从 utils/constants.js 解析出任何错误码")
        return

    bad = []
    for path in iter_source_files(ROOT):
        rel = path.relative_to(ROOT).as_posix()
        if rel == "utils/constants.js":
            continue
        body = read(path)
        for m in re.finditer(r"ERROR_CODES\.([A-Z][A-Z0-9_]*)", body):
            name = m.group(1)
            if name not in defined:
                bad.append(f"{rel} -> ERROR_CODES.{name}")
    if bad:
        res.fail("ERROR_CODES 引用有效", "引用了不存在的错误码: " + "; ".join(sorted(set(bad))))
    else:
        res.ok("ERROR_CODES 引用有效（无拼写错误）")


def check_required_utils(res: Result) -> None:
    required = ["utils/request.js", "utils/storage.js", "utils/date.js", "utils/constants.js"]
    missing = [f for f in required if not (ROOT / f).exists()]
    if missing:
        res.fail("utils 必备文件齐全", "缺少: " + ", ".join(missing))
    else:
        res.ok("utils 必备文件齐全")


def check_base_url_single_source(res: Result) -> None:
    """BASE_URL 必须只在 constants.js 定义一次。"""
    constants = ROOT / "utils" / "constants.js"
    if not constants.exists():
        res.fail("BASE_URL 单一定义", "utils/constants.js 不存在")
        return
    text = read(constants)
    if "BASE_URL" not in text:
        res.fail("BASE_URL 单一定义", "utils/constants.js 未定义 BASE_URL")
        return
    defined_elsewhere = []
    for path in iter_source_files(ROOT):
        rel = path.relative_to(ROOT).as_posix()
        if rel == "utils/constants.js":
            continue
        if re.search(r"const\s+BASE_URL\s*=", read(path)):
            defined_elsewhere.append(rel)
    if defined_elsewhere:
        res.fail("BASE_URL 单一定义", "重复定义于: " + ", ".join(defined_elsewhere))
    else:
        res.ok("BASE_URL 单一定义于 utils/constants.js")


def main() -> int:
    res = Result()
    check_app_json(res)
    check_required_utils(res)
    check_base_url_single_source(res)
    check_bare_requests(res)
    check_console_log(res)
    check_hardcoded_address(res)
    check_third_party(res)
    check_service_fields(res)
    check_error_code_refs(res)
    check_stats_empty_logic(res)
    check_checkin_text(res)
    check_about_license(res)

    for name in res.checks:
        print(f"  ✓ {name}")
    if res.failures:
        print()
        for detail in res.failures:
            print(f"  ✗ {detail}")
        print(f"\n{len(res.failures)} 项检查失败")
        return 1

    print(f"\n✓ {len(res.checks)} 项检查全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
