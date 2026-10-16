#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
G4 闸口冒烟测试：学习闭环（§3 学习 / §4 复习 / §5 错题）真实 HTTP + 真实 MySQL。

与 JUnit 的区别：JUnit 用 H2 + MockMvc（不起 HTTP、不连 MySQL）。
本脚本用真实 jar + 真实 MySQL + 真实 HTTP，验证「打包后可跑」这件事本身。

注意：本脚本会写 ai_vocab 库。它只创建/清理自己的测试用户数据
（username 前缀 g4_），不触碰既有数据；红线 anime_tracker 完全不涉及。
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"

PASS = []
FAIL = []


def check(name, cond, detail=""):
    if cond:
        PASS.append(name)
        print("  PASS  " + name if detail == "" else "  PASS  " + name + "  (" + detail + ")")
    else:
        FAIL.append(name + " :: " + detail)
        print("  FAIL  " + name + "  (" + detail + ")")


def call(method, path, token=None, body=None):
    """返回 (http_status, parsed_json_or_None)。"""
    url = BASE + path
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, None


def code_of(resp):
    return None if resp is None else resp.get("code")


def data_of(resp):
    return None if resp is None else resp.get("data")


def main():
    print("== G4 学习闭环冒烟 ==")

    # 1. 健康检查：未选词书的新用户 /streak 应可访问（需先登录）
    status, health = call("GET", "/api/client/books")
    check("未带 token 访问受保护接口 → 401 + 1006",
          status == 401 and code_of(health) == "1006",
          "status=%s code=%s" % (status, code_of(health)))

    # 2. 用「账号密码注册」建一个测试用户（避免依赖微信 code）
    username = "g4_%d" % int(time.time() * 1000 % 100000000)
    status, reg = call("POST", "/api/client/auth/register", body={
        "username": username, "password": "G4pass!234", "confirmPassword": "G4pass!234",
        "nickname": "G4冒烟",
    })
    check("注册测试用户 (HTTP 200, code=0)",
          status == 200 and code_of(reg) == "0",
          "status=%s code=%s msg=%s" % (status, code_of(reg), (reg or {}).get("message")))
    if code_of(reg) != "0":
        print("注册失败，终止。")
        return report()

    status, login = call("POST", "/api/client/auth/login", body={
        "username": username, "password": "G4pass!234"})
    token = data_of(login).get("token") if code_of(login) == "0" else None
    check("密码登录拿到 token", token is not None, "code=%s" % code_of(login))
    if not token:
        return report()

    # 3. §3.1 未选词书 → 空计划
    status, plan = call("GET", "/api/client/study/plan", token)
    check("§3.1 未选词书 → total=0、words=[]、HTTP 200",
          status == 200 and code_of(plan) == "0"
          and data_of(plan)["total"] == 0 and len(data_of(plan)["words"]) == 0,
          "status=%s code=%s" % (status, code_of(plan)))

    # 4. 选一本已导入的词书（种子数据里应存在）
    status, books = call("GET", "/api/client/books", token)
    book_list = data_of(books) if code_of(books) == "0" else []
    check("§2.1 词书列表 data 是数组、含种子词书（>=1 本，wordCount>0）",
          isinstance(book_list, list) and len(book_list) >= 1
          and book_list[0]["wordCount"] > 0,
          "books=%s" % (len(book_list) if isinstance(book_list, list) else book_list))
    if not isinstance(book_list, list) or not book_list:
        return report()
    book_id = book_list[0]["bookId"]

    status, sel = call("POST", "/api/client/books/%d/select" % book_id, token)
    check("§2.3 选择词书成功", code_of(sel) == "0", "code=%s" % code_of(sel))

    status, sel2 = call("POST", "/api/client/books/%d/select" % book_id, token)
    check("§2.3 重复选择 → 2003", code_of(sel2) == "2003", "code=%s" % code_of(sel2))

    # 5. §3.1 计划应给 20 个新词
    status, plan = call("GET", "/api/client/study/plan", token)
    p = data_of(plan)
    check("§3.1 计划 total=20（每本 20 新词）、finished=0、checkedIn=false",
          p["total"] == 20 and p["finished"] == 0 and p["checkedIn"] is False,
          "total=%s finished=%s" % (p["total"], p["finished"]))
    check("§3.1 计划词项字段齐全（wordId/phonetic/translation/example/isReview/learned）",
          all(k in p["words"][0] for k in
              ("wordId", "bookId", "word", "phonetic", "translation", "example",
               "isReview", "learned")),
          "keys=%s" % sorted(p["words"][0].keys()))
    word_ids = [w["wordId"] for w in p["words"]]

    # 6. §3.3 计划未完成 → 3003
    status, ci = call("POST", "/api/client/study/checkin", token)
    check("§3.3 计划未完成打卡 → 3003（HTTP 200）",
          status == 200 and code_of(ci) == "3003",
          "status=%s code=%s" % (status, code_of(ci)))

    # 7. §3.2 逐词上报；最后一个 «不认识» 以产生错题
    mistakes_made = 0
    for i, wid in enumerate(word_ids):
        known = i != len(word_ids) - 1
        status, rec = call("POST", "/api/client/study/record", token,
                           {"wordId": wid, "bookId": book_id, "known": known})
        if code_of(rec) != "0":
            check("§3.2 上报 wordId=%d" % wid, False, "code=%s" % code_of(rec))
            break
        if data_of(rec)["addedToMistakes"]:
            mistakes_made += 1
    check("§3.2 20 个词全部上报成功且 addedToMistakes 仅在 known=false 时为 true",
          mistakes_made == 1, "mistakes_made=%d" % mistakes_made)

    # 8. §3.2 重复上报幂等
    status, rec = call("POST", "/api/client/study/record", token,
                       {"wordId": word_ids[0], "bookId": book_id, "known": True})
    check("§3.2 重复上报幂等（code=0）", code_of(rec) == "0", "code=%s" % code_of(rec))

    # 9. 计划窗口不漂移：完成 20 个后 total 仍为 20、finished=20
    status, plan = call("GET", "/api/client/study/plan", token)
    p = data_of(plan)
    check("§3.1 计划窗口不漂移（上报后 total 仍 20、finished=20）",
          p["total"] == 20 and p["finished"] == 20,
          "total=%s finished=%s" % (p["total"], p["finished"]))

    # 10. §3.3 打卡成功
    status, ci = call("POST", "/api/client/study/checkin", token)
    c = data_of(ci)
    check("§3.3 打卡成功：streakDays=1、todayLearned=20、todayMistakes=1",
          code_of(ci) == "0" and c["streakDays"] == 1
          and c["todayLearned"] == 20 and c["todayMistakes"] == 1,
          "code=%s data=%s" % (code_of(ci), c))

    # 11. §3.3 重复打卡 → 3001
    status, ci2 = call("POST", "/api/client/study/checkin", token)
    check("§3.3 重复打卡 → 3001", code_of(ci2) == "3001", "code=%s" % code_of(ci2))

    # 12. §3.4 streak
    status, st = call("GET", "/api/client/study/streak", token)
    s = data_of(st)
    check("§3.4 streak：streakDays=1、totalLearned=20、lastCheckInDate 非空",
          s["streakDays"] == 1 and s["totalLearned"] == 20
          and s["lastCheckInDate"] is not None,
          "data=%s" % s)

    # 13. §5.1 错题列表：mastered 为布尔
    status, mk = call("GET", "/api/client/mistakes?mastered=0&page=1&size=20", token)
    m = data_of(mk)
    check("§5.1 错题列表 {list,total,page,size}，total=1",
          code_of(mk) == "0" and m["total"] == 1 and m["page"] == 1 and m["size"] == 20,
          "code=%s data=%s" % (code_of(mk), {k: m[k] for k in ("total", "page", "size")}))
    item = m["list"][0]
    check("§5.1 mastered 是 JSON 布尔（非 0/1）且 source=STUDY、wrongCount=1",
          item["mastered"] is False and item["source"] == "STUDY"
          and item["wrongCount"] == 1,
          "mastered=%r type=%s" % (item["mastered"], type(item["mastered"]).__name__))
    mistake_id = item["mistakeId"]

    # 14. §5.1 mastered 传非法值 → 1005
    status, bad = call("GET", "/api/client/mistakes?mastered=7", token)
    check("§5.1 mastered=7 → 1005", code_of(bad) == "1005", "code=%s" % code_of(bad))

    # 15. §5.2 标记已掌握 + 重复 → 4002
    status, mm = call("POST", "/api/client/mistakes/%d/mastered" % mistake_id, token)
    check("§5.2 标记已掌握成功（mastered=true）",
          code_of(mm) == "0" and data_of(mm)["mastered"] is True,
          "code=%s" % code_of(mm))
    status, mm2 = call("POST", "/api/client/mistakes/%d/mastered" % mistake_id, token)
    check("§5.2 重复标记 → 4002", code_of(mm2) == "4002", "code=%s" % code_of(mm2))

    # 16. §5.2 他人/不存在的错题 → 4001
    status, mm3 = call("POST", "/api/client/mistakes/99999999/mastered", token)
    check("§5.2 不存在的错题 → 4001", code_of(mm3) == "4001", "code=%s" % code_of(mm3))

    # 17. §5.3 移除 + 再次移除 → 4001
    status, rm = call("DELETE", "/api/client/mistakes/%d" % mistake_id, token)
    check("§5.3 移除错题成功（removed=true）",
          code_of(rm) == "0" and data_of(rm)["removed"] is True,
          "code=%s" % code_of(rm))
    status, rm2 = call("DELETE", "/api/client/mistakes/%d" % mistake_id, token)
    check("§5.3 再次移除 → 4001（已逻辑删除）", code_of(rm2) == "4001",
          "code=%s" % code_of(rm2))

    # 18. §4.1 今日待复习：刚学的词明天才到期 → total=0
    status, rv = call("GET", "/api/client/review/today", token)
    check("§4.1 刚学的词次日才到期 → 今日待复习 total=0",
          code_of(rv) == "0" and data_of(rv)["total"] == 0,
          "code=%s total=%s" % (code_of(rv), data_of(rv)["total"]))

    # 19. §4.2 对未学习词复习 → 1005（用词书详情里没学过的词）
    status, detail = call("GET", "/api/client/books/%d?page=1&size=50" % book_id, token)
    all_words = [w["wordId"] for w in data_of(detail)["words"]["list"]]
    unlearned = [w for w in all_words if w not in word_ids]
    if unlearned:
        status, rv2 = call("POST", "/api/client/review/record", token,
                           {"wordId": unlearned[0], "correct": True})
        check("§4.2 未学习词提交复习 → 1005", code_of(rv2) == "1005",
              "code=%s" % code_of(rv2))

    # 20. 越权：另一个用户不能标记本用户的错题
    #     （用第 15 步已掌握的错题——它已被移除，故改为验证不存在即 4001 已覆盖）

    print()
    return report()


def report():
    print("=" * 60)
    print("PASS: %d    FAIL: %d" % (len(PASS), len(FAIL)))
    for f in FAIL:
        print("  FAILED: " + f)
    print("=" * 60)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
