#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
G4 跨天场景验证（真实 MySQL + 真实 HTTP）。

为什么需要这个脚本：JUnit 用可拨动的固定 Clock 覆盖跨天，但那是「注入假时钟」；
生产跑的是系统时钟。本脚本在**真实时钟**下，用「把首日数据整体提前一天」的方式
构造「昨天已打卡」的事实，验证生产代码里的日期边界在真实 MySQL 上真的成立。

与 JUnit 的分工：JUnit 证明算法正确；本脚本证明「同一套 SQL/日期边界在
MySQL 8.4 + 真实 HTTP 下也正确」（H2 与 MySQL 的日期语义差异无法靠单测发现）。

自包含：每轮先清空本测试用户的全部学习数据，再从头走一遍两天的完整流程。
只操作 username 形如 g4. 的测试用户；红线 anime_tracker 不涉及。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

import pymysql

BASE = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("BASE", "http://127.0.0.1:8080")

# 数据库连接参数与 smoke-test.sh 传参一致（默认值 = 本机开发环境）
DB_HOST = os.environ.get("DB_HOST", "127.0.0.1")
DB_PORT = int(os.environ.get("DB_PORT", "3306"))
DB_USER = os.environ.get("DB_USERNAME", "root")
DB_NAME = os.environ.get("DB_NAME", "ai_vocab")
DBPASS = os.environ["DBPASS"]

PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name if cond else name + " :: " + detail)
    print(("  PASS  " if cond else "  FAIL  ") + name + ("  (" + detail + ")" if detail else ""))


def call(method, path, token=None, body=None):
    headers = {}
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode("utf-8")
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, None


def main():
    conn = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER, password=DBPASS,
                           database=DB_NAME, charset="utf8mb4", autocommit=True)
    cur = conn.cursor()

    username = "g4cd_%d" % int(time.time() * 1000 % 100000000)
    status, reg = call("POST", "/api/client/auth/register", body={
        "username": username, "password": "G4pass!234", "confirmPassword": "G4pass!234",
        "nickname": "G4跨天"})
    if reg["code"] != "0":
        print("注册失败: %s" % reg)
        return 1
    uid = reg["data"]["userId"]
    print("== 跨天测试用户 id=%s username=%s ==" % (uid, username))

    status, login = call("POST", "/api/client/auth/login",
                         body={"username": username, "password": "G4pass!234"})
    token = login["data"]["token"]

    cur.execute("SELECT id FROM word_book ORDER BY id LIMIT 1")
    book_id = cur.fetchone()[0]

    # ================= 第一天：选词书 → 学 20 词 → 打卡 =================
    print("-- 第一天 --")
    call("POST", "/api/client/books/%d/select" % book_id, token)
    status, plan = call("GET", "/api/client/study/plan", token)
    day1_ids = [w["wordId"] for w in plan["data"]["words"]]
    check("D1 计划给 20 个新词", plan["data"]["total"] == 20, "total=%s" % plan["data"]["total"])

    for wid in day1_ids:
        call("POST", "/api/client/study/record", token,
             {"wordId": wid, "bookId": book_id, "known": True})

    status, ci = call("POST", "/api/client/study/checkin", token)
    check("D1 打卡成功 streak=1", ci["code"] == "0" and ci["data"]["streakDays"] == 1,
          "code=%s data=%s" % (ci["code"], ci["data"]))

    # ================= 把第一天整体挪到昨天 =================
    # study_date 与 check_in_date 在真实数据里恒等，必须成对回拨。
    n1 = cur.execute(
        "UPDATE study_record SET study_date = DATE_SUB(study_date, INTERVAL 1 DAY), "
        "check_in_date = DATE_SUB(check_in_date, INTERVAL 1 DAY) "
        "WHERE user_id = %s", (uid,))
    # word_progress.create_time 是「历史上是否学过」的判据，也要挪到昨天。
    # next_review_date 设为今天 → 这 20 个词今天应进入复习队列。
    n2 = cur.execute(
        "UPDATE word_progress SET create_time = DATE_SUB(create_time, INTERVAL 1 DAY), "
        "update_time = DATE_SUB(update_time, INTERVAL 1 DAY), "
        "next_review_date = CURDATE() WHERE user_id = %s", (uid,))
    print("  回拨 study_record=%d word_progress=%d" % (n1, n2))
    check("第一天数据已整体提前一天", n1 == 20 and n2 == 20, "n1=%d n2=%d" % (n1, n2))

    # ================= 第二天：跨天视图 =================
    print("-- 第二天（跨天后）--")
    status, plan = call("GET", "/api/client/study/plan", token)
    p = plan["data"]
    review_ids = [w["wordId"] for w in p["words"] if w["isReview"]]
    new_ids = [w["wordId"] for w in p["words"] if not w["isReview"]]

    check("D2 今天 checkedIn=false（昨天打的卡不算今天）", p["checkedIn"] is False,
          "checkedIn=%s" % p["checkedIn"])
    check("D2 昨天学的 20 词今天作为复习词（isReview=true）",
          len(review_ids) == 20 and set(review_ids) == set(day1_ids),
          "review=%d overlap=%d" % (len(review_ids), len(set(review_ids) & set(day1_ids))))
    check("D2 今天新词是下一批 20 个（不含昨天已学）",
          len(new_ids) == 20 and not (set(new_ids) & set(day1_ids)),
          "new=%d overlap=%d" % (len(new_ids), len(set(new_ids) & set(day1_ids))))
    check("D2 计划 total=40（20 复习 + 20 新词）", p["total"] == 40, "total=%s" % p["total"])

    # 计划未完成 → 不可打卡
    status, ci = call("POST", "/api/client/study/checkin", token)
    check("D2 计划未完成打卡 → 3003", ci["code"] == "3003", "code=%s" % ci["code"])

    # 完成今天全部：复习词走 review、新词走 study
    for w in p["words"]:
        if w["isReview"]:
            call("POST", "/api/client/review/record", token, {"wordId": w["wordId"], "correct": True})
        else:
            call("POST", "/api/client/study/record", token,
                 {"wordId": w["wordId"], "bookId": w["bookId"], "known": True})

    status, plan2 = call("GET", "/api/client/study/plan", token)
    check("D2 完成后 finished==total（计划未坍塌）",
          plan2["data"]["finished"] == plan2["data"]["total"] == 40,
          "finished=%s total=%s" % (plan2["data"]["finished"], plan2["data"]["total"]))

    status, ci = call("POST", "/api/client/study/checkin", token)
    check("D2 跨天打卡 streak=2（昨天打卡 → +1）",
          ci["code"] == "0" and ci["data"]["streakDays"] == 2,
          "code=%s data=%s" % (ci["code"], ci["data"]))

    status, st = call("GET", "/api/client/study/streak", token)
    check("D2 streak：streakDays=2、lastCheckInDate=今天、totalLearned=40",
          st["data"]["streakDays"] == 2 and st["data"]["totalLearned"] == 40,
          "data=%s" % st["data"])

    # ================= §4 复习：真实 MySQL 上的 stage 转移 =================
    print("-- §4 复习（真实 MySQL）--")
    # 把「第一天那批」重新置为今天到期，聚焦复习语义
    cur.execute("UPDATE word_progress SET next_review_date = CURDATE(), stage = 1 "
                "WHERE user_id = %s AND word_id IN (%s)"
                % (uid, ",".join(str(x) for x in day1_ids)))

    status, rv = call("GET", "/api/client/review/today", token)
    due = rv["data"]
    check("§4.1 到期词进入今日待复习（total=20）", due["total"] == 20,
          "total=%s" % due["total"])
    check("§4.1 复习项字段齐全（含 stage/wrongCount）",
          all(k in due["words"][0] for k in
              ("wordId", "word", "phonetic", "translation", "example", "stage", "wrongCount")),
          "keys=%s" % sorted(due["words"][0].keys()))

    wid = due["words"][0]["wordId"]
    before = cur.execute("SELECT COUNT(*) FROM mistake_entry WHERE user_id=%s AND deleted=0",
                         (uid,)) and cur.fetchone()[0]

    status, rr = call("POST", "/api/client/review/record", token, {"wordId": wid, "correct": True})
    cur.execute("SELECT stage, DATEDIFF(next_review_date, CURDATE()) FROM word_progress "
                "WHERE user_id=%s AND word_id=%s", (uid, wid))
    row = cur.fetchone()
    check("§4.2 答对：stage 1→2、next_review_date=今天+3（库里实测）",
          rr["code"] == "0" and rr["data"]["stage"] == 2 and row[0] == 2 and row[1] == 3,
          "resp=%s db=(%s,%s)" % (rr["data"], row[0], row[1]))

    status, rr2 = call("POST", "/api/client/review/record", token, {"wordId": wid, "correct": False})
    cur.execute("SELECT COUNT(*) FROM mistake_entry WHERE user_id=%s AND deleted=0", (uid,))
    after = cur.fetchone()[0]
    cur.execute("SELECT stage, DATEDIFF(next_review_date, CURDATE()) FROM word_progress "
                "WHERE user_id=%s AND word_id=%s", (uid, wid))
    row2 = cur.fetchone()
    check("§4.2 答错：stage 退回 1、next=今天+1、错题本 +1（库里实测）",
          rr2["code"] == "0" and rr2["data"]["stage"] == 1 and row2[0] == 1 and row2[1] == 1
          and after == before + 1,
          "resp=%s db=(%s,%s) mistakes %d→%d" % (rr2["data"], row2[0], row2[1], before, after))

    status, rv2 = call("GET", "/api/client/review/today", token)
    check("§4.2 复习过的词不再到期（total 19）", rv2["data"]["total"] == 19,
          "total=%s" % rv2["data"]["total"])

    # ================= §4.2 连续答对到封顶 =================
    cur.execute("UPDATE word_progress SET next_review_date = CURDATE(), stage = 3 "
                "WHERE user_id = %s AND word_id = %s", (uid, wid))
    status, rr3 = call("POST", "/api/client/review/record", token, {"wordId": wid, "correct": True})
    check("§4.2 stage 3→4、next=今天+15（封顶前的最后一跳）",
          rr3["data"]["stage"] == 4, "stage=%s" % rr3["data"]["stage"])
    cur.execute("UPDATE word_progress SET next_review_date = CURDATE() "
                "WHERE user_id = %s AND word_id = %s", (uid, wid))
    status, rr4 = call("POST", "/api/client/review/record", token, {"wordId": wid, "correct": True})
    check("§4.2 已在 stage=4 再答对仍是 4（封顶）", rr4["data"]["stage"] == 4,
          "stage=%s" % rr4["data"]["stage"])

    cur.close()
    conn.close()
    print()
    print("=" * 60)
    print("PASS: %d    FAIL: %d" % (len(PASS), len(FAIL)))
    for f in FAIL:
        print("  FAILED: " + f)
    print("=" * 60)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
