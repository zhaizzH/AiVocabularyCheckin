"""
后端接口链路验证。

目的：小程序页面写完后，唯一可靠的验收方式是「用真实后端跑一遍真实请求」。
但手动点 11 个页面很慢，且空账号很多分支（无计划、无错题、无复习）根本走不到。

本脚本按 services/ 里每个函数的**真实调用方式**打后端，
逐字段断言响应结构，让「字段名写错/类型不同/语义反了」在页面开发前就暴露。

与 verify_miniprogram.py 的分工：
  那个查静态规范（地址是否散落、有没有 console.log）；
  这个查运行时契约（后端到底返回什么）。

前置：后端跑在 127.0.0.1:8080，MySQL 可用。
运行：python scripts/verify_api_flow.py
退出码：全部断言通过 0，否则 1。
"""

from __future__ import annotations

import json
import random
import string
import sys
import time
from typing import Any

import httpx

BASE_URL = "http://127.0.0.1:8080"
TIMEOUT = 90.0

# 与 utils/constants.js 的 AI_TIMEOUT 对应（秒）
AI_TIMEOUT = {"example": 15.0, "explain": 30.0, "report": 60.0}

failures: list[str] = []
passes: list[str] = []


def ok(label: str) -> None:
    passes.append(label)
    print(f"  \u2713 {label}")


def bad(label: str, detail: str) -> None:
    failures.append(f"{label}: {detail}")
    print(f"  \u2717 {label}\n      {detail}")


def check(label: str, condition: bool, detail: str = "") -> bool:
    if condition:
        ok(label)
    else:
        bad(label, detail)
    return condition


class Client:
    """按 Result 包装解析，与 utils/request.js 的行为完全一致。"""

    def __init__(self) -> None:
        self.token = ""

    def call(self, method: str, path: str, *, data: Any = None,
             auth: bool = True, timeout: float = 15.0) -> tuple[str, Any]:
        headers = {"Content-Type": "application/json"}
        if auth and self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        try:
            resp = httpx.request(method, BASE_URL + path, json=data,
                                 headers=headers, timeout=timeout)
        except httpx.TimeoutException:
            return "TIMEOUT", None
        except httpx.HTTPError as exc:
            return "NETWORK", str(exc)

        if resp.status_code == 401:
            return "1006", None

        body = resp.json()
        code = str(body.get("code", ""))
        if code != "0":
            return code, body.get("message", "")
        return "0", body.get("data")


def rand_name(prefix: str = "vf") -> str:
    suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=8))
    return f"{prefix}_{suffix}"


def assert_keys(label: str, obj: Any, keys: list[str]) -> None:
    if not isinstance(obj, dict):
        bad(label, f"期望对象，实际 {type(obj).__name__}: {obj!r}")
        return
    missing = [k for k in keys if k not in obj]
    if missing:
        bad(label, f"缺字段 {missing}；实际字段 {sorted(obj.keys())}")
    else:
        ok(label)


def section(title: str) -> None:
    print(f"\n=== {title} ===")


def main() -> int:
    c = Client()

    section("0. 后端可达性")
    try:
        r = httpx.get(BASE_URL + "/api/client/books", timeout=10.0)
    except httpx.HTTPError as exc:
        print(f"  \u2717 后端不可达 {BASE_URL}: {exc}")
        print("\n请先启动后端：cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=local")
        return 1
    check("后端可达（/api/client/books 无 token 返回 401 而非连接失败）",
          r.status_code == 401, f"实际 status={r.status_code}")

    section("1. 认证（services/auth.js）")
    username = rand_name()
    password = "Test123456"

    code, data = c.call("POST", "/api/client/auth/register",
                        data={"username": username, "password": password,
                              "nickname": "验证用户"}, auth=False)
    if check("register 返回 code 0", code == "0", f"code={code} {data}"):
        assert_keys("register 返回字段", data,
                    ["userId", "username", "nickname", "role"])

    code, data = c.call("POST", "/api/client/auth/register",
                        data={"username": username, "password": password}, auth=False)
    check("重复注册返回 1001 USERNAME_TAKEN", code == "1001", f"实际 code={code}")

    code, data = c.call("POST", "/api/client/auth/login",
                        data={"username": username, "password": password}, auth=False)
    if check("login 返回 code 0", code == "0", f"code={code} {data}"):
        assert_keys("login 返回字段", data, ["token", "userInfo"])
        c.token = (data or {}).get("token", "")
        check("login 返回非空 token", bool(c.token))

    code, data = c.call("POST", "/api/client/auth/login",
                        data={"username": username, "password": "wrong"}, auth=False)
    check("错误密码返回 1002 BAD_CREDENTIALS", code == "1002", f"实际 code={code}")

    code, data = c.call("GET", "/api/client/auth/profile")
    if check("profile 返回 code 0", code == "0", f"code={code} {data}"):
        assert_keys("profile 返回字段", data,
                    ["userId", "username", "nickname", "avatar", "role",
                     "createTime", "totalCheckInDays"])

    code, data = c.call("PUT", "/api/client/auth/profile",
                        data={"nickname": "验证改名"})
    if check("updateProfile 返回 code 0", code == "0", f"code={code} {data}"):
        check("updateProfile 生效", (data or {}).get("nickname") == "验证改名",
              f"nickname={(data or {}).get('nickname')}")

    section("2. 词库（services/book.js）")
    code, books = c.call("GET", "/api/client/books")
    if check("listBooks 返回 code 0", code == "0", f"code={code} {books}"):
        check("listBooks 返回裸数组（非分页对象）", isinstance(books, list),
              f"实际 {type(books).__name__}")
        if isinstance(books, list) and books:
            assert_keys("词书对象字段", books[0],
                        ["bookId", "name", "category", "wordCount",
                         "description", "selected", "learnedCount"])
            check("词书 category 为英文枚举值",
                  books[0]["category"] in ("CET4", "CET6", "KAOYAN"),
                  f"实际 category={books[0]['category']!r}")

    # 按分类过滤
    code, cet4 = c.call("GET", "/api/client/books?category=CET4")
    if check("listBooks(?category=CET4) 返回 code 0", code == "0", f"code={code}"):
        ok_only = isinstance(cet4, list) and all(b.get("category") == "CET4" for b in cet4)
        check("分类过滤只返回 CET4", ok_only,
              f"实际 categories={[b.get('category') for b in (cet4 or [])]}")

    target_book_id = None
    if isinstance(books, list) and books:
        target_book_id = books[0]["bookId"]

    if target_book_id is not None:
        code, detail = c.call("GET", f"/api/client/books/{target_book_id}?page=1&size=20")
        if check("getBookDetail 返回 code 0", code == "0", f"code={code} {detail}"):
            assert_keys("getBookDetail 顶层字段", detail,
                        ["bookId", "name", "category", "wordCount", "words"])
            words = (detail or {}).get("words") or {}
            assert_keys("getBookDetail.words 分页字段", words,
                        ["list", "total", "page", "size"])
            if words.get("list"):
                assert_keys("单词对象字段", words["list"][0],
                            ["wordId", "word", "phonetic", "translation", "example"])

        code, sel = c.call("POST", f"/api/client/books/{target_book_id}/select")
        if check("selectBook 返回 code 0", code == "0", f"code={code} {sel}"):
            check("selectBook.selected 为 true", (sel or {}).get("selected") is True,
                  f"实际 {sel!r}")

        code, sel2 = c.call("POST", f"/api/client/books/{target_book_id}/select")
        check("重复 selectBook 返回 2003 BOOK_ALREADY_SELECTED", code == "2003",
              f"实际 code={code}")

        code, unsel = c.call("DELETE", f"/api/client/books/{target_book_id}/select")
        check("unselectBook 返回 code 0", code == "0", f"code={code} {unsel}")

        code, _ = c.call("POST", f"/api/client/books/999999/select")
        check("不存在的词书返回 2001 BOOK_NOT_FOUND", code == "2001", f"实际 code={code}")

    section("3. 学习（services/study.js）")
    code, streak = c.call("GET", "/api/client/study/streak")
    if check("getStreak 返回 code 0", code == "0", f"code={code} {streak}"):
        assert_keys("streak 字段", streak,
                    ["streakDays", "totalLearned", "todayReviewCount", "lastCheckInDate"])

    code, plan = c.call("GET", "/api/client/study/plan")
    if check("getPlan 返回 code 0", code == "0", f"code={code} {plan}"):
        assert_keys("plan 字段", plan,
                    ["date", "total", "finished", "checkedIn", "words"])
        check("全新账号 total == 0（无已选词书）", (plan or {}).get("total") == 0,
              f"实际 total={(plan or {}).get('total')}")

    # 未完成计划时打卡应被拒
    code, msg = c.call("POST", "/api/client/study/checkin")
    check("空计划打卡返回 3002 NO_STUDY_PLAN", code == "3002", f"实际 code={code} msg={msg}")

    # 选中词书后应出现计划
    if target_book_id is not None:
        c.call("POST", f"/api/client/books/{target_book_id}/select")
        code, plan2 = c.call("GET", "/api/client/study/plan")
        if check("选书后 getPlan 返回 code 0", code == "0", f"code={code} {plan2}"):
            total = (plan2 or {}).get("total", 0)
            check("选书后 total > 0", total > 0, f"实际 total={total}")
            words = (plan2 or {}).get("words") or []
            check("plan.words 长度 == total", len(words) == total,
                  f"words={len(words)} total={total}")
            if words:
                assert_keys("plan 单词字段", words[0],
                            ["wordId", "bookId", "word", "phonetic", "translation",
                             "example", "isReview", "learned"])

            # 上报第一个词（已知）
            w0 = words[0]
            code, rec = c.call("POST", "/api/client/study/record",
                               data={"wordId": w0["wordId"],
                                     "bookId": w0["bookId"], "known": True})
            if check("recordStudy(known=true) 返回 code 0", code == "0",
                     f"code={code} {rec}"):
                assert_keys("recordStudy 字段", rec,
                            ["wordId", "learned", "addedToMistakes"])
                check("known=true 不入错题", (rec or {}).get("addedToMistakes") is False,
                      f"实际 {rec!r}")

            # 上报第二个词（不认识）
            if len(words) > 1:
                w1 = words[1]
                code, rec2 = c.call("POST", "/api/client/study/record",
                                    data={"wordId": w1["wordId"],
                                          "bookId": w1["bookId"], "known": False})
                if check("recordStudy(known=false) 返回 code 0", code == "0",
                         f"code={code} {rec2}"):
                    check("known=false 自动入错题",
                          (rec2 or {}).get("addedToMistakes") is True,
                          f"实际 {rec2!r}")

            code, plan3 = c.call("GET", "/api/client/study/plan")
            if check("上报后 getPlan 返回 code 0", code == "0", f"code={code}"):
                check("上报后 finished 递增",
                      (plan3 or {}).get("finished", 0) >= 1,
                      f"finished={(plan3 or {}).get('finished')}")
                check("上报后 total 不变（total 一旦确定不再变化）",
                      (plan3 or {}).get("total") == total,
                      f"before={total} after={(plan3 or {}).get('total')}")

    section("5. 错题（services/mistake.js）")
    code, mistakes = c.call("GET", "/api/client/mistakes?mastered=0&page=1&size=20")
    if check("listMistakes 返回 code 0", code == "0", f"code={code} {mistakes}"):
        assert_keys("mistakes 分页字段", mistakes, ["list", "total", "page", "size"])
        lst = (mistakes or {}).get("list") or []
        if lst:
            assert_keys("错题对象字段", lst[0],
                        ["mistakeId", "wordId", "word", "phonetic", "translation",
                         "source", "wrongCount", "mastered", "createTime"])
            check("错题 mastered 为 JSON 布尔（非 0/1 数字）",
                  isinstance(lst[0]["mastered"], bool),
                  f"实际 {type(lst[0]['mastered']).__name__}={lst[0]['mastered']!r}")
            check("错题 source 为英文枚举值",
                  lst[0]["source"] in ("STUDY", "REVIEW"),
                  f"实际 source={lst[0]['source']!r}")

            mid = lst[0]["mistakeId"]

            # explain 必须在 markMastered 之前调：标记掌握后该错题就不再出现在
            # mastered=0 列表里，之后再想找一条未掌握错题就得造数据。
            t0 = time.time()
            code, ex2 = c.call("POST", "/api/client/ai/explain",
                               data={"mistakeId": mid},
                               timeout=AI_TIMEOUT["explain"])
            elapsed = time.time() - t0
            if check(f"explainMistake 返回 code 0（耗时 {elapsed:.1f}s）", code == "0",
                     f"code={code} {ex2}"):
                assert_keys("explain 字段", ex2,
                            ["mistakeId", "explanation", "advice", "degraded"])
                check("explain.explanation 非空",
                      bool((ex2 or {}).get("explanation")), f"实际 {ex2!r}")
                check("explain.advice 非空",
                      bool((ex2 or {}).get("advice")), f"实际 {ex2!r}")

            code, mk = c.call("POST", f"/api/client/mistakes/{mid}/mastered")
            if check("markMastered 返回 code 0", code == "0", f"code={code} {mk}"):
                check("markMastered.mastered 为 true",
                      (mk or {}).get("mastered") is True, f"实际 {mk!r}")

            code, _ = c.call("POST", f"/api/client/mistakes/{mid}/mastered")
            check("重复 markMastered 返回 4002 MISTAKE_ALREADY_MASTERED",
                  code == "4002", f"实际 code={code}")

        code, _ = c.call("POST", "/api/client/mistakes/999999/mastered")
        check("不存在错题返回 4001 MISTAKE_NOT_FOUND", code == "4001", f"实际 code={code}")

    section("4. 复习（services/review.js）")
    code, review = c.call("GET", "/api/client/review/today")
    if check("getTodayReview 返回 code 0", code == "0", f"code={code} {review}"):
        assert_keys("review 字段", review, ["total", "words"])
        rw = (review or {}).get("words") or []
        check("review.total == len(words)", (review or {}).get("total") == len(rw),
              f"total={(review or {}).get('total')} len={len(rw)}")
        if rw:
            assert_keys("待复习单词字段", rw[0],
                        ["wordId", "word", "phonetic", "translation",
                         "example", "stage", "wrongCount"])
            code, rr = c.call("POST", "/api/client/review/record",
                              data={"wordId": rw[0]["wordId"], "correct": True})
            if check("recordReview(correct=true) 返回 code 0", code == "0",
                     f"code={code} {rr}"):
                assert_keys("recordReview 字段", rr,
                            ["wordId", "stage", "nextReviewDate"])
        else:
            # 新账号首复习安排在次日（SM-2 第一次间隔 1 天），今日为空是正确语义，
            # 不是缺陷。跳过而不是算失败，否则每跑一次都报一个无法修复的假失败。
            print("      \u2139 今日无待复习（SM-2 首复习在次日），跳过 recordReview 断言")

    section("7. 统计（services/stats.js）")
    code, heat = c.call("GET", "/api/client/stats/heatmap?weeks=12")
    if check("getHeatmap 返回 code 0", code == "0", f"code={code} {heat}"):
        assert_keys("heatmap 字段", heat, ["days"])
        days = (heat or {}).get("days") or []
        check("热力图 12 周 == 84 天", len(days) == 84, f"实际 {len(days)} 天")
        if days:
            assert_keys("热力图单日字段", days[0], ["date", "wordCount", "level"])
            levels = {d["level"] for d in days}
            check("level 取值在 0..3", levels.issubset({0, 1, 2, 3}), f"实际 {levels}")
            dates = [d["date"] for d in days]
            check("热力图日期连续且升序",
                  all(dates[i] < dates[i + 1] for i in range(len(dates) - 1)),
                  "日期未严格升序")

    code, mastery = c.call("GET", "/api/client/stats/mastery?days=30")
    if check("getMastery 返回 code 0", code == "0", f"code={code} {mastery}"):
        assert_keys("mastery 字段", mastery, ["points"])
        points = (mastery or {}).get("points") or []
        check("掌握度 30 天 == 30 点", len(points) == 30, f"实际 {len(points)} 点")
        if points:
            assert_keys("掌握度单点字段", points[0],
                        ["date", "learnedCount", "reviewCount",
                         "mistakeCount", "masteryRate"])

    section("6. AI（services/ai.js）")
    # 例句：需要一个真实 wordId
    word_id = None
    if target_book_id is not None:
        code, d = c.call("GET", f"/api/client/books/{target_book_id}?page=1&size=1")
        if code == "0" and ((d or {}).get("words") or {}).get("list"):
            word_id = d["words"]["list"][0]["wordId"]

    if word_id is not None:
        t0 = time.time()
        code, ex = c.call("POST", "/api/client/ai/example",
                          data={"wordId": word_id}, timeout=AI_TIMEOUT["example"])
        elapsed = time.time() - t0
        if check(f"getExample 返回 code 0（耗时 {elapsed:.1f}s）", code == "0",
                 f"code={code} {ex}"):
            assert_keys("example 字段", ex,
                        ["wordId", "example", "exampleTranslation",
                         "mnemonic", "degraded"])
            check("example.degraded 为布尔（降级是 200+code 0，不是错误路径）",
                  isinstance((ex or {}).get("degraded"), bool),
                  f"实际 {type((ex or {}).get('degraded')).__name__}")
            if (ex or {}).get("degraded"):
                print("      \u26a0 当前处于降级态（AI 不可用），example 为词库内置例句")
                check("降级时 mnemonic 为空串",
                      (ex or {}).get("mnemonic") == "",
                      f"实际 {((ex or {}).get('mnemonic'))!r}")

    # 报告：新账号累计天数 < 3，应为 5003
    t0 = time.time()
    code, rep = c.call("POST", "/api/client/ai/report", timeout=AI_TIMEOUT["report"])
    elapsed = time.time() - t0
    if code == "5003":
        ok(f"新账号 AI 报告返回 5003 AI_REPORT_DATA_INSUFFICIENT（{elapsed:.1f}s，后端直接拒绝未调 AI）")
    elif code == "0":
        ok(f"AI 报告返回 code 0（{elapsed:.1f}s）")
        assert_keys("report 字段", rep, ["report", "generatedAt", "degraded"])
        check("report.report 含三段字段",
              isinstance((rep or {}).get("report"), dict)
              and all(k in rep["report"] for k in ("overview", "weakness", "suggestion")),
              f"实际 {rep!r}")
    else:
        bad("AI 报告", f"期望 0 或 5003，实际 code={code} {rep}")

    section("8. 鉴权边界")
    saved = c.token
    c.token = ""
    code, _ = c.call("GET", "/api/client/study/plan")
    check("无 token 访问受保护接口返回 1006（或 HTTP 401）",
          code in ("1006", "401"), f"实际 code={code}")
    c.token = saved

    print(f"\n{'=' * 50}")
    if failures:
        print(f"{len(passes)} 项通过，{len(failures)} 项失败")
        return 1
    print(f"\u2713 {len(passes)} 项断言全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
