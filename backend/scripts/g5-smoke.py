#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
G5 闸口冒烟：AI 中转与降级（契约 §6）。

一等命题（implement.md 闸口表）：**ai-service 未启动时降级路径全绿**。
本脚本因此不启动 ai-service，故意让它不可达（默认 127.0.0.1:8000 无监听）。

用真实 MySQL + 真实 HTTP 打后端，逐条核对契约 §6 的响应形状与错误码。

用法：
    python backend/scripts/g5-smoke.py            # 默认后端 http://127.0.0.1:8080
    python backend/scripts/g5-smoke.py http://127.0.0.1:9090

注意：本脚本只创建/清理自己 `g5_` 前缀的测试用户，绝不触碰既有数据
（红线库 anime_tracker 与本库其它数据均不受影响）。
"""
import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("BASE", "http://127.0.0.1:8080")

PASS = 0
FAIL = 0


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


# ---------------------------------------------------------------
# 注册一个全新用户（真实微信 code2Session 在测试环境不可用，
# 故走用户名/密码注册这条真实可用路径）
# ---------------------------------------------------------------
suffix = str(int(__import__("time").time() * 1000))
username = "g5_" + suffix
password = "g5pass123"

status, payload, raw = http("POST", "/api/client/auth/register",
                            body={"username": username, "password": password,
                                  "nickname": "G5冒烟"})
if code_of(payload) != "0":
    print("FATAL 注册失败 status=%s payload=%s" % (status, raw[:300]))
    sys.exit(2)

status, payload, raw = http("POST", "/api/client/auth/login",
                            body={"username": username, "password": password})
token = data_of(payload).get("token") if code_of(payload) == "0" else None
if not token:
    print("FATAL 登录失败 payload=%s" % raw[:300])
    sys.exit(2)
print("== 测试用户 %s 登录成功 ==" % username)
print("== ai-service 应处于未启动状态（127.0.0.1:8000 无监听）==\n")

# ---------------------------------------------------------------
# A. §6.1 example —— 降级【不抛异常】是本次闸口的核心断言
# ---------------------------------------------------------------
# 先选词书 + 取一个词，才谈得上 AI 例句。
status, payload, raw = http("GET", "/api/client/books", token=token)
books = data_of(payload)
check("A0 §2.1 词书列表 code:0 且 data 是裸数组",
      code_of(payload) == "0" and isinstance(books, list) and len(books) > 0,
      "payload=%s" % raw[:200])

book_id = books[0]["bookId"] if books else None
# 契约：POST /api/client/books/{bookId}/select（路径参数，无请求体）
status, payload, raw = http("POST", "/api/client/books/%s/select" % book_id, token=token)
check("A0 §2.3 选词书成功", code_of(payload) == "0", raw[:200])

status, payload, raw = http("GET", "/api/client/study/plan", token=token)
plan = data_of(payload)
words = (plan or {}).get("words") or []
check("A0 §3.1 拿到今日词表", code_of(payload) == "0" and len(words) > 0,
      raw[:200])
word = words[0] if words else None

if word:
    status, payload, raw = http("POST", "/api/client/ai/example", token=token,
                                body={"wordId": word["wordId"]})
    check("A1 §6.1 example ai-service 未启动 → HTTP 200",
          status == 200, "status=%s" % status)
    check("A2 §6.1 example 降级 code:\"0\"（不得为 5001/5002）",
          code_of(payload) == "0", "code=%s" % code_of(payload))
    d = data_of(payload) or {}
    check("A3 §6.1 example degraded:true", d.get("degraded") is True,
          "degraded=%r" % d.get("degraded"))
    check("A4 §6.1 example 非空（来自词库兜底）",
          bool(d.get("example")), "example=%r" % d.get("example"))
    check("A5 §6.1 mnemonic 降级时为空串", d.get("mnemonic") == "",
          "mnemonic=%r" % d.get("mnemonic"))
    check("A6 §6.1 example 的 JSON 类型：degraded 必须是布尔",
          isinstance(d.get("degraded"), bool), "type=%s" % type(d.get("degraded")).__name__)

# 边界：不存在的单词 → 1005
status, payload, raw = http("POST", "/api/client/ai/example", token=token,
                            body={"wordId": 999999999})
check("A7 §6.1 不存在的 wordId → 1005", code_of(payload) == "1005",
      "code=%s" % code_of(payload))

# 边界：缺 wordId → 1005（@Valid）
status, payload, raw = http("POST", "/api/client/ai/example", token=token, body={})
check("A8 §6.1 缺 wordId → 1005", code_of(payload) == "1005",
      "code=%s" % code_of(payload))

# ---------------------------------------------------------------
# B. §6.2 explain —— 降级抛 5002（连接被拒，非超时）
# ---------------------------------------------------------------
# 造一个错题：学习上报时 known=false 会进错题本。
if word:
    status, payload, raw = http("POST", "/api/client/study/record", token=token,
                                body={"wordId": word["wordId"], "bookId": book_id,
                                      "known": False})
    check("B0 §3.2 学习上报（答错）成功", code_of(payload) == "0", raw[:200])

    status, payload, raw = http("GET", "/api/client/mistakes?mastered=0", token=token)
    mlist = (data_of(payload) or {}).get("list") or []
    check("B1 §5.1 错题本有 1 条", len(mlist) >= 1, "list=%s" % mlist[:1])
    if mlist:
        mistake_id = mlist[0]["mistakeId"]

        status, payload, raw = http("POST", "/api/client/ai/explain", token=token,
                                    body={"mistakeId": mistake_id})
        check("B2 §6.2 explain ai-service 未启动 → HTTP 200",
              status == 200, "status=%s" % status)
        check("B3 §6.2 explain 降级 → 5002（连接被拒=服务异常，非 5001）",
              code_of(payload) == "5002", "code=%s" % code_of(payload))
        check("B4 §6.2 explain 降级不返回 data",
              data_of(payload) is None, "data=%s" % data_of(payload))

    # 边界：不存在的错题 → 4001
    status, payload, raw = http("POST", "/api/client/ai/explain", token=token,
                                body={"mistakeId": 999999999})
    check("B5 §6.2 不存在的 mistakeId → 4001", code_of(payload) == "4001",
          "code=%s" % code_of(payload))

# ---------------------------------------------------------------
# C. §6.3 report —— 学习天数 < 3 → 5003（且不发起 HTTP）
# ---------------------------------------------------------------
status, payload, raw = http("POST", "/api/client/ai/report", token=token)
check("C1 §6.3 学习天数 < 3（新用户仅 0~1 天）→ 5003",
      code_of(payload) == "5003", "code=%s" % code_of(payload))
check("C2 §6.3 5003 不返回 data", data_of(payload) is None,
      "data=%s" % data_of(payload))

# 边界：无 token → 401 + 1006
status, payload, raw = http("POST", "/api/client/ai/example",
                            body={"wordId": word["wordId"] if word else 1})
check("C3 §6 无 token 访问 AI 接口 → HTTP 401 + 1006",
      status == 401 and code_of(payload) == "1006",
      "status=%s code=%s" % (status, code_of(payload)))

# ---------------------------------------------------------------
# D. 回归：AI 不可用不影响学习闭环（阶段 7 链路仍绿）
# ---------------------------------------------------------------
# 先把今日计划全部上报完——否则打卡会正确地返回 3003（计划未完成），
# 那样测的就不是「AI 不可用是否影响打卡」，而是我脚本自己少报了词。
if word:
    status, payload, raw = http("GET", "/api/client/study/plan", token=token)
    plan_words = (data_of(payload) or {}).get("words") or []
    for item in plan_words:
        if item.get("learned"):
            continue
        http("POST", "/api/client/study/record", token=token,
             body={"wordId": item["wordId"], "bookId": book_id, "known": True})
    status, payload, raw = http("GET", "/api/client/study/plan", token=token)
    d = data_of(payload) or {}
    check("D0 §3.1 计划已全部完成（finished == total）",
          d.get("finished") == d.get("total") and (d.get("total") or 0) > 0,
          "total=%s finished=%s" % (d.get("total"), d.get("finished")))

status, payload, raw = http("POST", "/api/client/study/checkin", token=token)
check("D1 §3.3 AI 不可用不影响打卡",
      code_of(payload) == "0", "code=%s" % code_of(payload))

status, payload, raw = http("GET", "/api/client/study/streak", token=token)
check("D2 §3.4 学习状态接口正常", code_of(payload) == "0", raw[:200])

status, payload, raw = http("GET", "/api/client/review/today", token=token)
check("D3 §4.1 复习接口正常", code_of(payload) == "0", raw[:200])

# ---------------------------------------------------------------
print("\n== 结果：%d PASS / %d FAIL ==" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
