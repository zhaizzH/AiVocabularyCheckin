#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段 9 闸口冒烟：统计模块（契约 §7）。

闸口命题（implement.md §9）：
  1. GET /stats/heatmap 返回 84 个点、日期连续、level 与 wordCount 匹配分档；
  2. GET /stats/mastery?days=30 返回 30 个点，masteryRate 与手工计算一致；
  3. 统计走 SQL 聚合（无原始记录加载到 Java）——由 Service 单测的
     「同一天 30 条流水聚合成 1 行」与 SQL 复查共同保证，本脚本不重复。

用**真实 MySQL + 真实 HTTP** 打后端，数据全部由真实接口产生
（注册 → 选词书 → 逐词学习 → 打卡），不直接改库。
分档边界用两个真实用户拉开：1 本词书 = 20 词（2 档上边界）、
2 本词书 = 40 词（3 档）。

用法：
    python backend/scripts/stage9-stats-smoke.py                    # 默认 http://127.0.0.1:8080
    python backend/scripts/stage9-stats-smoke.py http://127.0.0.1:9090

注意：本脚本只创建/清理自己 `stage9_` 前缀的测试用户，绝不触碰既有数据
（红线库 anime_tracker 与本库其它数据均不受影响）。
"""
import datetime
import json
import sys
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"

PASS = 0
FAIL = 0
USER_SEQ = 0


def check(label, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("PASS  %s" % label)
    else:
        FAIL += 1
        print("FAIL  %s  %s" % (label, detail))


def http(method, path, token=None, body=None):
    """返回 (status, json_dict_or_None, raw_text)。"""
    url = BASE + path
    data = None
    headers = {"Content-Type": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else None, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw) if raw else None, raw
        except ValueError:
            return e.code, None, raw


def code_of(payload):
    return payload.get("code") if isinstance(payload, dict) else None


def data_of(payload):
    return payload.get("data") if isinstance(payload, dict) else None


def expected_level(word_count):
    """契约 §7.1 分档：0 / 1–10 / 11–20 / 21+。"""
    if word_count <= 0:
        return 0
    if word_count <= 10:
        return 1
    if word_count <= 20:
        return 2
    return 3


def parse_date(text):
    return datetime.datetime.strptime(text, "%Y-%m-%d").date()


def assert_contiguous(label, dates):
    """断言日期序列严格逐日连续（无断点、无重复、无乱序）。"""
    ok = True
    detail = ""
    for i in range(1, len(dates)):
        if dates[i] != dates[i - 1] + datetime.timedelta(days=1):
            ok = False
            detail = "第 %d 个点 %s 不是 %s 的次曰" % (i, dates[i], dates[i - 1])
            break
    check(label, ok, detail)


def register_and_login(prefix, book_count):
    """注册真实用户 → 登录 → 选 book_count 本词书。返回 (token, book_ids)。"""
    # 用户名契约：4-20 位字母/数字/下划线。
    # stage9(6) + 时间戳后 11 位 + 序号(2) = 19 位，且同毫秒多次调用也不撞名。
    global USER_SEQ
    USER_SEQ += 1
    suffix = str(int(time.time() * 1000))[-11:]
    username = "stage9%s%02d" % (suffix, USER_SEQ)
    password = "stage9pass"

    status, payload, raw = http("POST", "/api/client/auth/register",
                                body={"username": username, "password": password,
                                      "nickname": "阶段9冒烟"})
    if code_of(payload) != "0":
        print("FATAL 注册失败 status=%s payload=%s" % (status, raw[:300]))
        sys.exit(2)

    status, payload, raw = http("POST", "/api/client/auth/login",
                                body={"username": username, "password": password})
    token = data_of(payload).get("token") if code_of(payload) == "0" else None
    if not token:
        print("FATAL 登录失败 payload=%s" % raw[:300])
        sys.exit(2)

    status, payload, raw = http("GET", "/api/client/books", token=token)
    books = data_of(payload) or []
    if len(books) < book_count:
        print("FATAL 词书不足 %d 本（实际 %d 本）" % (book_count, len(books)))
        sys.exit(2)

    book_ids = [b["bookId"] for b in books[:book_count]]
    for book_id in book_ids:
        status, payload, raw = http("POST", "/api/client/books/%s/select" % book_id,
                                    token=token)
        if code_of(payload) != "0":
            print("FATAL 选词书失败 book=%s payload=%s" % (book_id, raw[:200]))
            sys.exit(2)
    return token, book_ids


def finish_plan_and_checkin(token, wrong_word_ids=()):
    """把今日计划全部上报（指定词标「不认识」），再打卡。返回 (total, finished, code)。"""
    status, payload, raw = http("GET", "/api/client/study/plan", token=token)
    plan = data_of(payload) or {}
    words = plan.get("words") or []
    if not words:
        return 0, 0, None

    for item in words:
        known = item["wordId"] not in wrong_word_ids
        http("POST", "/api/client/study/record", token=token,
             body={"wordId": item["wordId"], "bookId": item["bookId"],
                   "known": known})

    status, payload, raw = http("GET", "/api/client/study/plan", token=token)
    plan = data_of(payload) or {}
    total = plan.get("total") or 0
    finished = plan.get("finished") or 0

    status, payload, raw = http("POST", "/api/client/study/checkin", token=token)
    return total, finished, code_of(payload)


# ===============================================================
# 准备两个真实用户：U1 学 1 本（20 词）、U2 学 2 本（40 词）
# ===============================================================
print("== 后端 %s（真实 MySQL + 真实 HTTP）==" % BASE)

token1, books1 = register_and_login("a", 1)
token2, books2 = register_and_login("b", 2)

# U1：20 新词，前两个标「不认识」（进错题本，用于掌握度）
status, payload, raw = http("GET", "/api/client/study/plan", token=token1)
plan_words_1 = (data_of(payload) or {}).get("words") or []
wrong_ids = {w["wordId"] for w in plan_words_1[:2]}
total1, finished1, checkin1 = finish_plan_and_checkin(token1, wrong_ids)
check("A0 U1 计划完成（20 词）且打卡成功",
      total1 == 20 and finished1 == 20 and checkin1 == "0",
      "total=%s finished=%s checkin=%s" % (total1, finished1, checkin1))

total2, finished2, checkin2 = finish_plan_and_checkin(token2)
check("A1 U2 计划完成（2 本词书 = 40 词）且打卡成功",
      total2 == 40 and finished2 == 40 and checkin2 == "0",
      "total=%s finished=%s checkin=%s" % (total2, finished2, checkin2))

# ===============================================================
# B. §7.1 热力图
# ===============================================================
status, payload, raw = http("GET", "/api/client/stats/heatmap", token=token1)
check("B0 §7.1 heatmap HTTP 200 + code:0", status == 200 and code_of(payload) == "0",
      "status=%s code=%s" % (status, code_of(payload)))

days = (data_of(payload) or {}).get("days") or []
check("B1 §7.1 默认返回恰好 84 个点（12 周 × 7 天）", len(days) == 84,
      "len=%s" % len(days))

if len(days) == 84:
    dates = [parse_date(d["date"]) for d in days]
    assert_contiguous("B2 §7.1 84 个点日期严格逐日连续（无断日）", dates)

    # 末点必须是「今天」——用打卡响应里的 checkInDate 作为服务端今天的事实来源，
    # 避免脚本假设本机时区与后端时区一致。
    status, payload, raw = http("GET", "/api/client/study/streak", token=token1)
    streak = data_of(payload) or {}
    today = streak.get("lastCheckInDate")
    check("B3 §7.1 序列末点 = 服务端今天（取自打卡日期）", days[-1]["date"] == today,
          "last=%s today=%s" % (days[-1]["date"], today))

    # 日期串必须是无 T 的裸日期（JacksonConfig 失效会在此暴露）
    check("B4 §7.1 日期为裸 'yyyy-MM-dd'（不含 T）",
          all("T" not in d["date"] for d in days))

    # 逐点核对分档：level 必须由 wordCount 按 0/1–10/11–20/21+ 得出
    bad = [d for d in days if d["level"] != expected_level(d["wordCount"])]
    check("B5 §7.1 全部 84 点的 level 与 wordCount 分档一致", not bad,
          "不一致：%s" % bad[:3])

    # U1 今天 20 词 → 上边界 2 档
    today_point = days[-1]
    check("B6 §7.1 U1 今日 20 词 → wordCount=20 且 level=2（卡在 2 档上边界）",
          today_point["wordCount"] == 20 and today_point["level"] == 2,
          "point=%s" % today_point)

    # 未打卡的日子必须全是 0 词 0 档（新用户只有今天有数据）
    idle = days[:-1]
    check("B7 §7.1 未打卡的日子补零（wordCount=0 / level=0）",
          all(d["wordCount"] == 0 and d["level"] == 0 for d in idle),
          "非零空闲日：%s" % [d for d in idle if d["wordCount"] != 0][:3])

status, payload, raw = http("GET", "/api/client/stats/heatmap?weeks=1", token=token1)
check("B8 §7.1 weeks=1 → 7 个点",
      len((data_of(payload) or {}).get("days") or []) == 7,
      "len=%s" % len((data_of(payload) or {}).get("days") or []))

# U2：40 词 → 3 档（21+）
status, payload, raw = http("GET", "/api/client/stats/heatmap", token=token2)
days2 = (data_of(payload) or {}).get("days") or []
check("B9 §7.1 U2 今日 40 词 → level=3（21+ 档，跨 1/2 档上界）",
      len(days2) == 84 and days2[-1]["wordCount"] == 40 and days2[-1]["level"] == 3,
      "last=%s" % (days2[-1] if days2 else None))

# ===============================================================
# C. §7.2 掌握度曲线
# ===============================================================
# U1 有 2 条错题，把其中 1 条标记已掌握 → 掌握度应为 50。
status, payload, raw = http("GET", "/api/client/mistakes?mastered=0", token=token1)
mlist = (data_of(payload) or {}).get("list") or []
check("C0 §5.1 U1 错题本有 2 条（前两词标了不认识）", len(mlist) == 2,
      "len=%s" % len(mlist))
if mlist:
    status, payload, raw = http("POST",
                                "/api/client/mistakes/%s/mastered" % mlist[0]["mistakeId"],
                                token=token1)
    check("C1 §5.2 标记 1 条已掌握成功", code_of(payload) == "0", raw[:200])

status, payload, raw = http("GET", "/api/client/stats/mastery", token=token1)
check("C2 §7.2 mastery HTTP 200 + code:0", status == 200 and code_of(payload) == "0",
      "status=%s code=%s" % (status, code_of(payload)))

points = (data_of(payload) or {}).get("points") or []
check("C3 §7.2 默认返回恰好 30 个点", len(points) == 30, "len=%s" % len(points))

if len(points) == 30:
    p_dates = [parse_date(p["date"]) for p in points]
    assert_contiguous("C4 §7.2 30 个点日期严格逐日连续", p_dates)
    check("C5 §7.2 序列末点 = 服务端今天", points[-1]["date"] == today,
          "last=%s today=%s" % (points[-1]["date"], today))

    # 手工算掌握度：错题总数 = 未掌握 1（另 1 条已移除出未掌握列表）+ 已掌握 1
    status, payload, raw = http("GET", "/api/client/mistakes?mastered=1", token=token1)
    mastered_list = (data_of(payload) or {}).get("list") or []
    mastered_count = len(mastered_list)
    # 未掌握的还剩 1 条（mlist 里已标记的那条不再出现）
    status, payload, raw = http("GET", "/api/client/mistakes?mastered=0", token=token1)
    unmastered_count = len((data_of(payload) or {}).get("list") or [])
    total_mistakes = mastered_count + unmastered_count
    expected_rate = round(mastered_count * 100.0 / total_mistakes) if total_mistakes else 100
    check("C6 §7.2 手工计数：已掌握 1 / 总数 2 → 期望掌握度 50",
          mastered_count == 1 and total_mistakes == 2 and expected_rate == 50,
          "mastered=%s total=%s rate=%s" % (mastered_count, total_mistakes, expected_rate))
    check("C7 §7.2 今日点 masteryRate 与手工计算一致",
          points[-1]["masteryRate"] == expected_rate,
          "api=%s expected=%s" % (points[-1]["masteryRate"], expected_rate))

    # 今日点：learnedCount = 20（U1 今天 20 条 STUDY 流水）
    check("C8 §7.2 今日点 learnedCount=20（U1 今日 20 条学习流水）",
          points[-1]["learnedCount"] == 20, "point=%s" % points[-1])

    # 补零日：三计数为 0，但 masteryRate 为存量快照（同一值）
    check("C9 §7.2 无数据日三计数补零",
          all(p["learnedCount"] == 0 and p["reviewCount"] == 0
              and p["mistakeCount"] == 0 for p in points[:-1]),
          "非零日：%s" % [p for p in points[:-1]
                          if p["learnedCount"] or p["reviewCount"] or p["mistakeCount"]][:3])
    check("C10 §7.2 掌握度是存量快照，补零日也取同一值",
          all(p["masteryRate"] == expected_rate for p in points),
          "distinct=%s" % sorted({p["masteryRate"] for p in points}))

status, payload, raw = http("GET", "/api/client/stats/mastery?days=7", token=token1)
check("C11 §7.2 days=7 → 7 个点",
      len((data_of(payload) or {}).get("points") or []) == 7,
      "len=%s" % len((data_of(payload) or {}).get("points") or []))

# ===============================================================
# D. 空态与鉴权
# ===============================================================
token3, _ = register_and_login("c", 1)
status, payload, raw = http("GET", "/api/client/stats/heatmap", token=token3)
days3 = (data_of(payload) or {}).get("days") or []
check("D0 §7.1 新用户无数据 → HTTP 200 + 84 个 level=0 的点（空态非错误）",
      status == 200 and code_of(payload) == "0" and len(days3) == 84
      and all(d["level"] == 0 for d in days3),
      "status=%s len=%s" % (status, len(days3)))

status, payload, raw = http("GET", "/api/client/stats/mastery", token=token3)
p3 = (data_of(payload) or {}).get("points") or []
check("D1 §7.2 新用户无错题 → masteryRate=100（无未掌握薄弱点，而非 0）",
      len(p3) == 30 and p3[-1]["masteryRate"] == 100,
      "len=%s last=%s" % (len(p3), p3[-1] if p3 else None))

status, payload, raw = http("GET", "/api/client/stats/heatmap")
check("D2 §7 无 token 访问统计接口 → HTTP 401 + 1006",
      status == 401 and code_of(payload) == "1006",
      "status=%s code=%s" % (status, code_of(payload)))

status, payload, raw = http("GET", "/api/client/stats/heatmap?weeks=abc", token=token1)
check("D3 §7 weeks 非数字 → HTTP 200 + 1005（参数错误走业务码，不是 400）",
      status == 200 and code_of(payload) == "1005",
      "status=%s code=%s" % (status, code_of(payload)))

status, payload, raw = http("GET", "/api/client/stats/heatmap?weeks=0", token=token1)
check("D4 §7 weeks=0 → 回落默认 84（前端初始态常传 0）",
      code_of(payload) == "0"
      and len((data_of(payload) or {}).get("days") or []) == 84,
      "len=%s" % len((data_of(payload) or {}).get("days") or []))

# ---------------------------------------------------------------
print("\n== 结果：%d PASS / %d FAIL ==" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
