#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段 10 闸口冒烟：管理模块（契约 §8.1 – §8.10）。真实 jar + 真实 MySQL + 真实 HTTP。

闸口命题（implement.md §10）：
  1. 普通用户访问 /api/admin/** 一律 403 + 1007；ADMIN 放行；无 token 401 + 1006；
  2. 词书列表 == admin-prototype.html 演示的 3 本种子词书，且**无** selected/learnedCount；
  3. 导入的坏行计数、整份非法 2004、分类白名单 1005 与契约一致；
  4. 单词增/改/删使 word_count 精确 ±1，删除是**逻辑**删除（行仍在库里 deleted=1）；
  5. 禁用用户后其 token **下一个请求**就 401 + 1003；禁 ADMIN → 1005；漏传 status → 1005；
  6. 删词书连带逻辑删除其单词；
  7. ai-stats 的降级计数随真实 AI 降级增长，features 顺序固定。

与 JUnit 的分工：JUnit 用 H2 + MockMvc；本脚本证明「打包后 + MySQL 8.4 + 真实 HTTP」
下契约仍成立（MySQL 与 H2 的分页/聚合/日期语义差异无法靠单测发现）。

数据安全：本脚本只创建/清理自己 `g6adm_` / `g6usr_` 前缀的用户，
以及自己导入的词书（name 前缀 `G6冒烟`）；脚本末尾按用户名精确物理清理。
绝不触碰既有数据；红线库 anime_tracker 完全不涉及。

需要 DBPASS 环境变量（与 g4-crossday.py 同一约定）：
    DBPASS=xxx python backend/scripts/stage10-admin-smoke.py
    DBPASS=xxx python backend/scripts/stage10-admin-smoke.py http://127.0.0.1:9090
"""
import datetime
import json
import os
import sys
import time
import urllib.error
import urllib.request

import pymysql

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
DBPASS = os.environ["DBPASS"]

PASS, FAIL = [], []

def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name if cond else name + " :: " + detail)
    print(("  PASS  " if cond else "  FAIL  ") + name + ("  (" + detail + ")" if detail else ""))

def call(method, path, token=None, body=None, raw_body=None, content_type=None):
    headers = {}
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if raw_body is not None:
        data = raw_body
        headers["Content-Type"] = content_type
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            text = r.read().decode("utf-8")
            return r.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8")
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, None

def code_of(resp):
    return None if resp is None else resp.get("code")

def data_of(resp):
    return None if resp is None else resp.get("data")

def multipart(fields):
    """构造 multipart/form-data 请求体（只用标准库，避免额外依赖）。"""
    boundary = "----G6SmokeBoundary%d" % int(time.time() * 1000)
    lines = []
    for name, value in fields:
        lines.append(("--%s\r\n" % boundary).encode("utf-8"))
        if isinstance(value, tuple):
            filename, content = value
            lines.append(('Content-Disposition: form-data; name="%s"; filename="%s"\r\n'
                          % (name, filename)).encode("utf-8"))
            lines.append(b"Content-Type: application/octet-stream\r\n\r\n")
            lines.append(content if isinstance(content, bytes) else content.encode("utf-8"))
            lines.append(b"\r\n")
        else:
            lines.append(('Content-Disposition: form-data; name="%s"\r\n\r\n'
                          % name).encode("utf-8"))
            lines.append(str(value).encode("utf-8"))
            lines.append(b"\r\n")
    lines.append(("--%s--\r\n" % boundary).encode("utf-8"))
    return b"".join(lines), "multipart/form-data; boundary=%s" % boundary

def unique_username(prefix):
    """契约：用户名 4–20 位字母/数字/下划线。prefix(6) + 11 位 = 17 位。"""
    return "%s%d" % (prefix, int(time.time() * 1000) % 100000000000)

def finish_plan_and_checkin(token):
    """把今日计划全部上报（全部标认识），再打卡。返回打卡 code。"""
    status, plan = call("GET", "/api/client/study/plan", token)
    for item in (data_of(plan) or {}).get("words") or []:
        call("POST", "/api/client/study/record", token,
             {"wordId": item["wordId"], "bookId": item["bookId"], "known": True})
    status, ci = call("POST", "/api/client/study/checkin", token)
    return code_of(ci)

# ===============================================================
def main():
    conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password=DBPASS,
                           database="ai_vocab", charset="utf8mb4", autocommit=True)
    cur = conn.cursor()
    print("== 阶段 10 管理模块冒烟 %s（真实 jar + 真实 MySQL）==" % BASE)

    admin_username = unique_username("g6adm_")
    user_username = unique_username("g6usr_")
    PASSWORD = "G6pass!234"

    # ---------------------------------------------------------------
    # A. 权限边界（§8 头部）
    # ---------------------------------------------------------------
    status, resp = call("GET", "/api/admin/books")
    check("A0 §8 无 token 访问管理端 → HTTP 401 + 1006",
          status == 401 and code_of(resp) == "1006",
          "status=%s code=%s" % (status, code_of(resp)))

    # 普通用户：注册 → 登录（role 来自 token，故必须先改库再登录）
    status, reg = call("POST", "/api/client/auth/register",
                       body={"username": user_username, "password": PASSWORD,
                             "confirmPassword": PASSWORD, "nickname": "G6普通用户"})
    if code_of(reg) != "0":
        print("FATAL 注册普通用户失败: %s" % reg)
        return 2
    user_id = data_of(reg)["userId"]
    status, login = call("POST", "/api/client/auth/login",
                         body={"username": user_username, "password": PASSWORD})
    user_token = data_of(login)["token"]

    status, resp = call("GET", "/api/admin/books", token=user_token)
    check("A1 §8 普通 USER 访问管理端 → HTTP 403 + 1007（不是 401、不是 404）",
          status == 403 and code_of(resp) == "1007",
          "status=%s code=%s" % (status, code_of(resp)))

    # 管理员：注册 → 提权（仅本脚本自己的 g6adm_ 用户）→ 登录
    status, reg = call("POST", "/api/client/auth/register",
                       body={"username": admin_username, "password": PASSWORD,
                             "confirmPassword": PASSWORD, "nickname": "G6管理员"})
    if code_of(reg) != "0":
        print("FATAL 注册管理员失败: %s" % reg)
        return 2
    admin_id = data_of(reg)["userId"]
    cur.execute("UPDATE user SET role = 'ADMIN' WHERE id = %s", (admin_id,))
    status, login = call("POST", "/api/client/auth/login",
                         body={"username": admin_username, "password": PASSWORD})
    admin_token = data_of(login)["token"]

    # ---------------------------------------------------------------
    # B. §8.8 管理端词书列表
    # ---------------------------------------------------------------
    status, resp = call("GET", "/api/admin/books", token=admin_token)
    books = data_of(resp)
    check("B0 §8.8 词书列表 HTTP 200 + code:0 + data 为裸数组",
          status == 200 and code_of(resp) == "0" and isinstance(books, list),
          "status=%s code=%s type=%s" % (status, code_of(resp), type(books).__name__))

    by_cat = {b["category"]: b for b in (books or []) if isinstance(b, dict)}
    check("B1 §8.8 三本种子词书齐全且 wordCount 与 S3 一致（7508 / 5651 / 9602）",
          by_cat.get("CET4", {}).get("wordCount") == 7508
          and by_cat.get("CET6", {}).get("wordCount") == 5651
          and by_cat.get("KAOYAN", {}).get("wordCount") == 9602,
          "counts=%s" % {k: v.get("wordCount") for k, v in by_cat.items()})

    seed_book_ids = sorted(b["bookId"] for b in (books or []))

    check("B2 §8.8 元素含 createTime 且**不含** selected / learnedCount（管理端无用户维度）",
          all("createTime" in b for b in (books or []))
          and all("selected" not in b and "learnedCount" not in b for b in (books or [])),
          "keys=%s" % (sorted((books or [{}])[0].keys()),))

    check("B3 §8.8 createTime 为 'YYYY-MM-DD HH:mm:ss'（JacksonConfig 生效）",
          all(len(b["createTime"]) == 19 and "T" not in b["createTime"]
              for b in (books or [])),
          "sample=%s" % ((books or [{}])[0].get("createTime"),))

    # ---------------------------------------------------------------
    # C. §8.1 词库导入
    # ---------------------------------------------------------------
    good_lines = [
        '{"word":"g6alpha","uk":"ae","translations":[{"translation":"甲","type":"n"}],'
        '"sentences":[{"sentence":"G6 alpha sentence.","translation":"甲句"}]}',
        '{"word":"g6beta","uk":"be","translations":[{"translation":"乙","type":"n"}]}',
    ]
    bad_line = '{"uk":"no-word-field","translations":[]}'

    body, ctype = multipart([
        ("file", ("g6.jsonl", "\n".join(good_lines + [bad_line]) + "\n")),
        ("name", "G6冒烟词书"),
        ("category", "CET4"),
    ])
    status, resp = call("POST", "/api/admin/books/import", token=admin_token,
                        raw_body=body, content_type=ctype)
    d = data_of(resp) or {}
    check("C0 §8.1 导入 3 行（1 行缺 word）→ success=2 / skipped=1 / 有 bookId",
          status == 200 and code_of(resp) == "0"
          and d.get("success") == 2 and d.get("skipped") == 1
          and isinstance(d.get("bookId"), int),
          "status=%s data=%s" % (status, d))
    imported_book_id = d.get("bookId")

    # 导入后 word_count 必须被 COUNT 校准为 2（不是 3）
    status, resp = call("GET", "/api/admin/books", token=admin_token)
    imported = [b for b in (data_of(resp) or []) if b["bookId"] == imported_book_id]
    check("C1 §8.1 导入后 wordCount 校正为实际合法行数 2（坏行不计入）",
          len(imported) == 1 and imported[0]["wordCount"] == 2,
          "imported=%s" % imported)

    # 分类不在白名单 → 1005
    body, ctype = multipart([
        ("file", ("g6.jsonl", good_lines[0] + "\n")),
        ("name", "G6冒烟非法分类"),
        ("category", "TOEFL"),
    ])
    status, resp = call("POST", "/api/admin/books/import", token=admin_token,
                        raw_body=body, content_type=ctype)
    check("C2 §8.1 category 非白名单 → 1005",
          status == 200 and code_of(resp) == "1005",
          "status=%s code=%s" % (status, code_of(resp)))

    # 整份无合法行 → 2004，且不留空壳词书
    before = len(call("GET", "/api/admin/books", token=admin_token)[1]["data"])
    body, ctype = multipart([
        ("file", ("bad.jsonl", bad_line + "\n" + bad_line + "\n")),
        ("name", "G6冒烟全坏行"),
        ("category", "CET4"),
    ])
    status, resp = call("POST", "/api/admin/books/import", token=admin_token,
                        raw_body=body, content_type=ctype)
    after = len(call("GET", "/api/admin/books", token=admin_token)[1]["data"])
    check("C3 §8.1 整份非法 → 2004，且不留 0 词空壳词书",
          status == 200 and code_of(resp) == "2004" and after == before,
          "status=%s code=%s before=%s after=%s" % (status, code_of(resp), before, after))

    # ---------------------------------------------------------------
    # D. §8.2 – §8.5 单词管理
    # ---------------------------------------------------------------
    status, resp = call("GET", "/api/admin/words?bookId=%d&page=1&size=1" % imported_book_id,
                        token=admin_token)
    d = data_of(resp) or {}
    check("D0 §8.2 单词分页结构 {list,total,page,size}，元素含 wordId",
          status == 200 and code_of(resp) == "0"
          and d.get("total") == 2 and d.get("page") == 1 and d.get("size") == 1
          and len(d.get("list") or []) == 1 and "wordId" in d["list"][0],
          "data=%s" % d)

    status, resp = call("GET", "/api/admin/words?bookId=%d&keyword=g6alph" % imported_book_id,
                        token=admin_token)
    d = data_of(resp) or {}
    check("D1 §8.2 keyword 模糊搜索命中 1 条",
          d.get("total") == 1 and d["list"][0]["word"] == "g6alpha",
          "data=%s" % d)

    # 新增
    status, resp = call("POST", "/api/admin/words", token=admin_token,
                        body={"bookId": imported_book_id, "word": "g6gamma",
                              "phonetic": "ga", "translation": "n. 丙", "example": "G6 gamma."})
    d = data_of(resp) or {}
    new_word_id = d.get("wordId")
    check("D2 §8.3 新增单词成功，响应含 wordId 且字段回显",
          status == 200 and code_of(resp) == "0" and isinstance(new_word_id, int)
          and d.get("word") == "g6gamma" and d.get("translation") == "n. 丙",
          "status=%s data=%s" % (status, d))

    raw_count = cur.execute("SELECT word_count FROM word_book WHERE id = %s", (imported_book_id,))
    cur.execute("SELECT word_count FROM word_book WHERE id = %s", (imported_book_id,))
    check("D3 §8.3 新增后词书 wordCount = 3（+1）",
          cur.fetchone()[0] == 3, "raw=%s" % raw_count)

    status, resp = call("POST", "/api/admin/words", token=admin_token,
                        body={"bookId": 99999999, "word": "ghost"})
    check("D4 §8.3 词书不存在 → 2001",
          code_of(resp) == "2001", "code=%s" % code_of(resp))

    status, resp = call("POST", "/api/admin/words", token=admin_token,
                        body={"bookId": imported_book_id, "word": "   "})
    count_now = call("GET", "/api/admin/books", token=admin_token)[1]["data"]
    wc = [b["wordCount"] for b in count_now if b["bookId"] == imported_book_id][0]
    check("D5 §8.3 word 为空白 → 1005，且 wordCount 未被改动",
          code_of(resp) == "1005" and wc == 3, "code=%s wc=%s" % (code_of(resp), wc))

    # 修改（只传 translation）
    status, resp = call("PUT", "/api/admin/words/%d" % new_word_id, token=admin_token,
                        body={"translation": "n. 丙（改）"})
    d = data_of(resp) or {}
    check("D6 §8.4 只改 translation，未传字段（word/phonetic）保持原值",
          code_of(resp) == "0" and d.get("translation") == "n. 丙（改）"
          and d.get("word") == "g6gamma" and d.get("phonetic") == "ga",
          "data=%s" % d)

    status, resp = call("PUT", "/api/admin/words/99999999", token=admin_token,
                        body={"translation": "x"})
    check("D7 §8.4 单词不存在 → 1005（契约无 2005）",
          code_of(resp) == "1005", "code=%s" % code_of(resp))

    # 删除（逻辑）
    status, resp = call("DELETE", "/api/admin/words/%d" % new_word_id, token=admin_token)
    d = data_of(resp) or {}
    check("D8 §8.5 删除单词 → {wordId, deleted:true}",
          code_of(resp) == "0" and d.get("wordId") == new_word_id and d.get("deleted") is True,
          "data=%s" % d)

    cur.execute("SELECT deleted FROM word WHERE id = %s", (new_word_id,))
    row = cur.fetchone()
    check("D9 §8.5 删除是**逻辑**删除：行仍在库中且 deleted=1（非物理 DELETE）",
          row is not None and row[0] == 1, "row=%s" % (row,))

    cur.execute("SELECT word_count FROM word_book WHERE id = %s", (imported_book_id,))
    check("D10 §8.5 删除后词书 wordCount 回到 2（-1）",
          cur.fetchone()[0] == 2, "")

    status, resp = call("DELETE", "/api/admin/words/99999999", token=admin_token)
    check("D11 §8.5 删除不存在的单词 → 1005",
          code_of(resp) == "1005", "code=%s" % code_of(resp))

    # ---------------------------------------------------------------
    # E. §8.6 / §8.7 用户管理
    # ---------------------------------------------------------------
    status, resp = call("GET", "/api/admin/users?keyword=%s" % user_username, token=admin_token)
    d = data_of(resp) or {}
    item = (d.get("list") or [{}])[0]
    check("E0 §8.6 用户列表按用户名搜索命中 1 条，字段齐全",
          code_of(resp) == "0" and d.get("total") == 1
          and item.get("userId") == user_id and item.get("username") == user_username
          and item.get("role") == "USER" and item.get("status") == 1
          and item.get("totalCheckInDays") == 0 and "createTime" in item,
          "item=%s" % item)

    check("E1 §8.6 响应不含 passwordHash / password / deleted",
          all(k not in item for k in ("passwordHash", "password", "deleted")),
          "keys=%s" % sorted(item.keys()))

    # 让该用户真实打卡一天，再看 totalCheckInDays
    books_resp = call("GET", "/api/client/books", token=user_token)[1]["data"]
    seed_book = [b for b in books_resp if b["wordCount"] == 7508][0]
    call("POST", "/api/client/books/%d/select" % seed_book["bookId"], token=user_token)
    checkin_code = finish_plan_and_checkin(user_token)
    check("E2 §3.3 普通用户真实完成打卡（用于验证打卡天数聚合）",
          checkin_code == "0", "code=%s" % checkin_code)

    status, resp = call("GET", "/api/admin/users?keyword=%s" % user_username, token=admin_token)
    item = (data_of(resp) or {}).get("list")[0]
    check("E3 §8.6 totalCheckInDays 由 SQL 聚合得出 = 1",
          item.get("totalCheckInDays") == 1, "item=%s" % item)

    # 禁用 → 该用户 token 下一个请求即 401 + 1003
    status, resp = call("PUT", "/api/admin/users/%d/status" % user_id, token=admin_token,
                        body={"status": 0})
    d = data_of(resp) or {}
    check("E4 §8.7 禁用用户 → {userId, status:0}",
          code_of(resp) == "0" and d.get("userId") == user_id and d.get("status") == 0,
          "data=%s" % d)

    status, resp = call("GET", "/api/client/auth/profile", token=user_token)
    check("E5 §8.7 禁用**即时生效**：同一 token 下一请求 → HTTP 401 + 1003",
          status == 401 and code_of(resp) == "1003",
          "status=%s code=%s" % (status, code_of(resp)))

    status, resp = call("PUT", "/api/admin/users/%d/status" % user_id, token=admin_token,
                        body={"status": 1})
    check("E6 §8.7 重新启用 → status:1",
          code_of(resp) == "0" and (data_of(resp) or {}).get("status") == 1,
          "data=%s" % data_of(resp))

    status, resp = call("GET", "/api/client/auth/profile", token=user_token)
    check("E7 §8.7 启用后同一 token 恢复可用",
          status == 200 and code_of(resp) == "0", "status=%s" % status)

    status, resp = call("PUT", "/api/admin/users/%d/status" % admin_id, token=admin_token,
                        body={"status": 0})
    check("E8 §8.7 禁用 ADMIN 账号 → 1005",
          code_of(resp) == "1005", "code=%s" % code_of(resp))

    status, resp = call("PUT", "/api/admin/users/%d/status" % user_id, token=admin_token,
                        body={})
    cur.execute("SELECT status FROM user WHERE id = %s", (user_id,))
    check("E9 §8.7 漏传 status → 1005，且绝不把用户静默禁用",
          code_of(resp) == "1005" and cur.fetchone()[0] == 1,
          "code=%s" % code_of(resp))

    status, resp = call("PUT", "/api/admin/users/%d/status" % user_id, token=admin_token,
                        body={"status": 2})
    check("E10 §8.7 status 取非法值 2 → 1005",
          code_of(resp) == "1005", "code=%s" % code_of(resp))

    # ---------------------------------------------------------------
    # F. §8.10 AI 监控
    # ---------------------------------------------------------------
    status, resp = call("GET", "/api/admin/monitor/ai-stats", token=admin_token)
    d = data_of(resp) or {}
    feats = d.get("features") or []
    check("F0 §8.10 ai-stats 结构齐全（含 totalCalls/successRate/degradedCount/registeredUsers）",
          status == 200 and code_of(resp) == "0"
          and all(k in d for k in ("totalCalls", "successRate", "degradedCount",
                                   "registeredUsers", "features", "recentEvents")),
          "keys=%s" % sorted(d.keys()))

    check("F1 §8.10 features 固定按 example/explain/report 三项（零调用也占位）",
          [f["feature"] for f in feats] == ["example", "explain", "report"],
          "features=%s" % [f.get("feature") for f in feats])

    check("F2 §8.10 registeredUsers 为真实注册用户数（含两个冒烟用户）",
          d.get("registeredUsers") >= 2, "registeredUsers=%s" % d.get("registeredUsers"))

    # ai-service 未启动 → example 走降级，degradedCount 必须增长
    before_degraded = d.get("degradedCount") or 0
    word_id = call("GET", "/api/admin/words?bookId=%d&page=1&size=1" % imported_book_id,
                   token=admin_token)[1]["data"]["list"][0]["wordId"]
    status, resp = call("POST", "/api/client/ai/example", token=user_token,
                        body={"wordId": word_id})
    check("F3 §6.1 AI 不可用时 example 仍 code:0 + degraded:true（真实降级）",
          status == 200 and code_of(resp) == "0"
          and (data_of(resp) or {}).get("degraded") is True,
          "status=%s data=%s" % (status, data_of(resp)))

    status, resp = call("GET", "/api/admin/monitor/ai-stats", token=admin_token)
    d = data_of(resp) or {}
    events = d.get("recentEvents") or []
    check("F4 §8.10 降级被计入 degradedCount（+1）",
          (d.get("degradedCount") or 0) >= before_degraded + 1,
          "before=%s after=%s" % (before_degraded, d.get("degradedCount")))

    check("F5 §8.10 降级进入 recentEvents 且最新在前（首条 feature=example）",
          len(events) >= 1 and events[0].get("feature") == "example"
          and events[0].get("reason") in ("TIMEOUT", "ERROR", "CIRCUIT_OPEN"),
          "events[0]=%s" % (events[0] if events else None))

    check("F6 §8.10 successRate 为数字且 0<=x<=100（无调用时为 100）",
          isinstance(d.get("successRate"), (int, float))
          and 0 <= d["successRate"] <= 100,
          "successRate=%s" % d.get("successRate"))

    # ---------------------------------------------------------------
    # G. §8.9 删除词书（连带逻辑删除单词）
    # ---------------------------------------------------------------
    status, resp = call("DELETE", "/api/admin/books/%d" % imported_book_id, token=admin_token)
    d = data_of(resp) or {}
    check("G0 §8.9 删除词书 → {bookId, deleted:true}",
          code_of(resp) == "0" and d.get("bookId") == imported_book_id
          and d.get("deleted") is True, "data=%s" % d)

    cur.execute("SELECT deleted FROM word_book WHERE id = %s", (imported_book_id,))
    book_row = cur.fetchone()
    cur.execute("SELECT COUNT(*) FROM word WHERE book_id = %s AND deleted = 0", (imported_book_id,))
    alive_words = cur.fetchone()[0]
    check("G1 §8.9 词书逻辑删除（行仍在 deleted=1）且其下单词**连带**逻辑删除（存活 0 条）",
          book_row is not None and book_row[0] == 1 and alive_words == 0,
          "book=%s alive_words=%s" % (book_row, alive_words))

    status, resp = call("GET", "/api/admin/books", token=admin_token)
    ids = [b["bookId"] for b in (data_of(resp) or [])]
    check("G2 §8.9 已删词书不再出现在列表",
          imported_book_id not in ids, "ids=%s" % ids)

    status, resp = call("DELETE", "/api/admin/books/99999999", token=admin_token)
    check("G3 §8.9 删除不存在的词书 → 2001",
          code_of(resp) == "2001", "code=%s" % code_of(resp))

    # ---------------------------------------------------------------
    # H. 回归：种子词书计数未被管理端操作污染
    # ---------------------------------------------------------------
    status, resp = call("GET", "/api/admin/books", token=admin_token)
    by_cat_after = {b["category"]: b["wordCount"] for b in (data_of(resp) or [])}
    check("H0 回归：3 本种子词书 wordCount 仍为 7508 / 5651 / 9602（未被管理端操作影响）",
          by_cat_after.get("CET4") == 7508 and by_cat_after.get("CET6") == 5651
          and by_cat_after.get("KAOYAN") == 9602,
          "counts=%s" % by_cat_after)

    status, resp = call("GET", "/api/admin/words?bookId=%d&page=1&size=5" % seed_book_ids[0],
                        token=admin_token)
    d = data_of(resp) or {}
    check("H1 回归：种子词书单词分页 total 与 wordCount 一致",
          d.get("total") == by_cat_after.get("CET4"),
          "total=%s" % d.get("total"))

    # ---------------------------------------------------------------
    # 清理：只清理本脚本自己创建的数据（按用户名 / name 前缀精确定位）
    # ---------------------------------------------------------------
    try:
        cur.execute("SELECT id FROM word_book WHERE name LIKE 'G6冒烟%%'")
        own_books = [r[0] for r in cur.fetchall()]
        for bid in own_books:
            cur.execute("DELETE FROM word WHERE book_id = %s", (bid,))
            cur.execute("DELETE FROM word_book WHERE id = %s", (bid,))
        for uid in (user_id, admin_id):
            cur.execute("DELETE FROM study_record WHERE user_id = %s", (uid,))
            cur.execute("DELETE FROM word_progress WHERE user_id = %s", (uid,))
            cur.execute("DELETE FROM mistake_entry WHERE user_id = %s", (uid,))
            cur.execute("DELETE FROM user_word_book WHERE user_id = %s", (uid,))
            cur.execute("DELETE FROM user WHERE id = %s", (uid,))
        print("  （已清理 %d 本自建词书与 2 个冒烟用户）" % len(own_books))
    except Exception as e:  # 清理失败不影响闸口结论，但必须可见
        print("  WARN 清理残留：%s" % e)
    conn.close()

    print("\n== 结果：%d PASS / %d FAIL ==" % (len(PASS), len(FAIL)))
    for name in FAIL:
        print("  FAILED: %s" % name)
    return 1 if FAIL else 0

if __name__ == "__main__":
    sys.exit(main())
