#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
管理端接口契约验收（直接打真实后端，不依赖浏览器）。

覆盖 frontend/src/services/*.ts 的 5 个资源、11 个接口，核对：
  - 字段名与类型（前端 types/ 定义是否与后端一致）
  - 错误码（1002/1003/1005/1006/1007/2001/2004）
  - 联动语义（wordCount 增减、禁用后该用户 1003）
  - 数据形状（books 裸数组 vs words/users 分页包装）

前置：
  1. 后端在 127.0.0.1:8080 运行
  2. 已执行 python scripts/create-admin.py（admin / Admin@12345）

用法：
  PYTHONIOENCODING=utf-8 python scripts/verify_admin_api.py
"""
import json
import secrets
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"
ADMIN_USER = "admin"
ADMIN_PASS = "Admin@12345"

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


def check_eq(label, actual, expected):
    check(label, actual == expected, "期望 %r，实际 %r" % (expected, actual))


def call(path, data=None, token=None, method=None, raw_body=None,
         content_type="application/json"):
    url = BASE + path
    if raw_body is not None:
        body = raw_body
    elif data is not None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
    else:
        body = None
    req = urllib.request.Request(url, data=body,
                                 method=method or ("POST" if body else "GET"))
    req.add_header("Content-Type", content_type)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"code": "NON_JSON", "message": raw[:200], "data": None}


def multipart_import(token, name, category, content: bytes, filename="data.jsonl"):
    """
    构造 multipart 请求。

    注意结尾必须 `--boundary--\\r\\n`，且**最后一段 body 也要以 \\r\\n 结尾**。
    少这个 CRLF 后端会把文件流读坏，返回 9001 而不是 2004（我踩过）。
    """
    boundary = "----verify" + secrets.token_hex(8)
    parts = []
    for field, value in (("name", name), ("category", category)):
        parts.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
                      % (boundary, field, value)).encode("utf-8"))
    if not content.endswith(b"\r\n"):
        content = content + b"\r\n"
    parts.append(("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
                  "Content-Type: application/octet-stream\r\n\r\n"
                  % (boundary, filename)).encode("utf-8") + content)
    parts.append(("--%s--\r\n" % boundary).encode("utf-8"))
    return call("/api/admin/books/import", token=token, raw_body=b"".join(parts),
                content_type="multipart/form-data; boundary=" + boundary)


def main() -> int:
    print("=" * 72)
    print("管理端接口契约验收")
    print("=" * 72)

    # ==================== 1. 登录与角色 ====================
    print("\n[1] 登录与角色")
    st, r = call("/api/client/auth/login",
                 {"username": ADMIN_USER, "password": ADMIN_PASS})
    check_eq("ADMIN 登录 HTTP 200", st, 200)
    check_eq("ADMIN 登录 code == '0'（字符串）", r.get("code"), "0")
    data = r.get("data") or {}
    check("响应含 token", isinstance(data.get("token"), str) and len(data["token"]) > 20)
    info = data.get("userInfo") or {}
    check_eq("userInfo.role == 'ADMIN'", info.get("role"), "ADMIN")
    # 前端 types/auth.ts 的 UserInfo 字段必须与后端一致
    expect_keys = {"userId", "username", "nickname", "avatar", "role"}
    check_eq("userInfo 字段集合与 types/auth.ts 一致",
             set(info.keys()), expect_keys)
    token = data["token"]

    st, r = call("/api/client/auth/login",
                 {"username": ADMIN_USER, "password": "wrong-password"})
    check_eq("密码错误 code == 1002", r.get("code"), "1002")

    st, r = call("/api/admin/books")
    check_eq("无 token HTTP 401", st, 401)
    check_eq("无 token code == 1006", r.get("code"), "1006")

    # 普通用户
    uname = "vadm_" + secrets.token_hex(3)
    call("/api/client/auth/register",
         {"username": uname, "password": "Probe12345", "nickname": "普通用户"})
    st, r = call("/api/client/auth/login",
                 {"username": uname, "password": "Probe12345"})
    user_token = (r.get("data") or {}).get("token")
    st, r = call("/api/admin/books", token=user_token)
    check_eq("普通用户访问 /api/admin/** HTTP 403", st, 403)
    check_eq("普通用户 code == 1007（前端据此不清 token）", r.get("code"), "1007")

    # ==================== 2. 词书列表 ====================
    print("\n[2] GET /api/admin/books")
    st, r = call("/api/admin/books", token=token)
    check_eq("HTTP 200", st, 200)
    books = r.get("data")
    check("data 是**裸数组**（不是分页包装）", isinstance(books, list),
          "实际类型 %s" % type(books).__name__)
    listed = {b["name"]: b for b in (books or [])}
    check_eq("含四级核心词汇", "四级核心词汇" in listed, True)
    check_eq("四级 wordCount == 7508",
             listed.get("四级核心词汇", {}).get("wordCount"), 7508)
    check_eq("六级 wordCount == 5651",
             listed.get("六级核心词汇", {}).get("wordCount"), 5651)
    check_eq("考研 wordCount == 9602",
             listed.get("考研核心词汇", {}).get("wordCount"), 9602)
    first = (books or [{}])[0]
    check_eq("元素字段与 types/book.ts 的 AdminBook 一致",
             set(first.keys()),
             {"bookId", "name", "category", "wordCount", "description", "createTime"})
    check_eq("createTime 是字符串（不是时间戳）",
             isinstance(first.get("createTime"), str), True)
    cet4 = listed.get("四级核心词汇", {})
    book_id = cet4.get("bookId")

    # ==================== 3. 单词分页 ====================
    print("\n[3] GET /api/admin/words")
    st, r = call("/api/admin/words?bookId=%s&page=1&size=3" % book_id, token=token)
    page = r.get("data") or {}
    check_eq("data 是**分页包装**", isinstance(page, dict), True)
    check_eq("分页字段与 types/common.ts 的 PageResult 一致",
             set(page.keys()), {"list", "total", "page", "size"})
    check_eq("total == 7508", page.get("total"), 7508)
    check_eq("list 长度 == size", len(page.get("list") or []), 3)
    w0 = (page.get("list") or [{}])[0]
    check_eq("单词字段与 types/word.ts 的 Word 一致",
             set(w0.keys()), {"wordId", "word", "phonetic", "translation", "example"})

    st, r = call("/api/admin/words?bookId=%s&keyword=aban&page=1&size=5" % book_id,
                 token=token)
    kp = r.get("data") or {}
    check("关键词 aban 有命中", (kp.get("total") or 0) > 0,
          "total=%s" % kp.get("total"))
    check_eq("首条命中 abandon",
             ((kp.get("list") or [{}])[0]).get("word"), "abandon")

    st, r = call("/api/admin/words?page=1&size=2", token=token)
    allp = r.get("data") or {}
    check("不传 bookId 时跨全书库（total > 20000）",
          (allp.get("total") or 0) > 20000, "total=%s" % allp.get("total"))

    # ==================== 4. 单词 CRUD 与 wordCount 联动 ====================
    print("\n[4] 单词增删改 + wordCount 联动")
    probe = "vprobe" + secrets.token_hex(3)
    st, r = call("/api/admin/words",
                 {"bookId": book_id, "word": probe, "phonetic": "/p/",
                  "translation": "n. 探针", "example": "probe."}, token=token)
    check_eq("新增 code == '0'", r.get("code"), "0")
    new_id = (r.get("data") or {}).get("wordId")
    check("返回 wordId", isinstance(new_id, int))

    st, r = call("/api/admin/books", token=token)
    after = {b["name"]: b["wordCount"] for b in (r.get("data") or [])}
    check_eq("新增后四级 wordCount == 7509", after.get("四级核心词汇"), 7509)

    st, r = call("/api/admin/words/%s" % new_id, {"translation": "n. 探针改"},
                 token=token, method="PUT")
    check_eq("修改后 translation 生效",
             (r.get("data") or {}).get("translation"), "n. 探针改")

    st, r = call("/api/admin/words/%s" % new_id, token=token, method="DELETE")
    check_eq("删除 code == '0'", r.get("code"), "0")
    check_eq("删除返回 deleted == true", (r.get("data") or {}).get("deleted"), True)

    st, r = call("/api/admin/books", token=token)
    restored = {b["name"]: b["wordCount"] for b in (r.get("data") or [])}
    check_eq("删除后 wordCount 回到 7508", restored.get("四级核心词汇"), 7508)

    st, r = call("/api/admin/words", {"bookId": book_id, "translation": "x"}, token=token)
    check_eq("新增缺 word → 1005", r.get("code"), "1005")

    # ==================== 5. 用户管理与禁用联动 ====================
    print("\n[5] 用户管理 + 禁用联动")
    st, r = call("/api/admin/users?page=1&size=3", token=token)
    up = r.get("data") or {}
    check_eq("用户分页字段 = PageResult", set(up.keys()),
             {"list", "total", "page", "size"})
    u0 = (up.get("list") or [{}])[0]
    check_eq("用户字段与 types/user.ts 的 AdminUser 一致", set(u0.keys()),
             {"userId", "username", "nickname", "role", "status",
              "totalCheckInDays", "createTime"})
    check("status 是数字 0/1", u0.get("status") in (0, 1),
          "实际 %r" % u0.get("status"))

    st, r = call("/api/admin/users?keyword=%s" % uname, token=token)
    target = ((r.get("data") or {}).get("list") or [{}])[0]
    check_eq("按用户名搜索命中", target.get("username"), uname)
    uid = target.get("userId")

    st, r = call("/api/admin/users/%s/status" % uid, {"status": 0},
                 token=token, method="PUT")
    check_eq("禁用 code == '0'", r.get("code"), "0")
    check_eq("禁用后 status == 0", (r.get("data") or {}).get("status"), 0)

    st, r = call("/api/client/books", token=user_token)
    check_eq("被禁用用户用旧 token 请求 HTTP 401", st, 401)
    check_eq("被禁用用户 code == 1003", r.get("code"), "1003")

    st, r = call("/api/client/auth/login",
                 {"username": uname, "password": "Probe12345"})
    check_eq("被禁用用户重新登录 code == 1003", r.get("code"), "1003")

    st, r = call("/api/admin/users/%s/status" % uid, {"status": 1},
                 token=token, method="PUT")
    check_eq("启用恢复 code == '0'", r.get("code"), "0")

    admin_id = info.get("userId")
    st, r = call("/api/admin/users/%s/status" % admin_id, {"status": 0},
                 token=token, method="PUT")
    check_eq("禁用 ADMIN 被拒 code == 1005", r.get("code"), "1005")

    # ==================== 6. JSONL 导入 ====================
    print("\n[6] JSONL 导入")
    good1 = {"word": "vimp" + secrets.token_hex(2), "uk": "/a/",
             "translations": [{"type": "n", "translation": "一"}],
             "sentences": [{"sentence": "A.", "translation": "甲。"}]}
    good2 = {"word": "vimp" + secrets.token_hex(2), "uk": "/b/",
             "translations": [{"type": "n", "translation": "二"}],
             "sentences": []}
    content = ("\n".join([json.dumps(good1), "this is not json",
                          json.dumps(good2), json.dumps({"x": 1})])).encode("utf-8")
    st, r = multipart_import(token, "验收探针书" + secrets.token_hex(2), "CET6", content)
    imp = r.get("data") or {}
    check_eq("导入 code == '0'", r.get("code"), "0")
    check_eq("成功 2 条", imp.get("success"), 2)
    check_eq("跳过 2 条（坏行计数正确）", imp.get("skipped"), 2)
    check("返回 bookId", isinstance(imp.get("bookId"), int))
    imported_book = imp.get("bookId")

    st, r = multipart_import(token, "非法分类书", "INVALID_CAT", content)
    check_eq("非法 category → 1005", r.get("code"), "1005")

    st, r = multipart_import(token, "全坏行书", "CET4",
                             b"not json\nstill not json\n")
    check_eq("全坏行 → 2004", r.get("code"), "2004")

    # ==================== 7. 删除词书 ====================
    print("\n[7] 删除词书")
    if imported_book:
        st, r = call("/api/admin/books/%s" % imported_book, token=token, method="DELETE")
        check_eq("删除词书 code == '0'", r.get("code"), "0")
        st, r = call("/api/admin/books", token=token)
        names = [b["name"] for b in (r.get("data") or [])]
        check("探针词书已不在列表", all("验收探针书" not in n for n in names))

    st, r = call("/api/admin/books/999999", token=token, method="DELETE")
    check_eq("删除不存在词书 → 2001", r.get("code"), "2001")

    # ==================== 8. 监控 ====================
    print("\n[8] 监控")
    st, r = call("/api/admin/monitor/ai-stats", token=token)
    mon = r.get("data") or {}
    check_eq("监控字段与 types/monitor.ts 的 AiStats 一致", set(mon.keys()),
             {"totalCalls", "successRate", "degradedCount", "registeredUsers",
              "features", "recentEvents"})
    check("successRate 是数字", isinstance(mon.get("successRate"), (int, float)))
    check("features 有 3 项", len(mon.get("features") or []) == 3,
          "实际 %s" % len(mon.get("features") or []))
    feats = {f.get("feature") for f in (mon.get("features") or [])}
    check_eq("features 覆盖三功能", feats, {"example", "explain", "report"})
    if mon.get("features"):
        check_eq("feature 元素字段", set(mon["features"][0].keys()),
                 {"feature", "total", "success", "degraded", "timeout",
                  "error", "avgCostMs"})
    check("recentEvents 是数组", isinstance(mon.get("recentEvents"), list))
    if mon.get("recentEvents"):
        check_eq("降级事件字段", set(mon["recentEvents"][0].keys()),
                 {"time", "feature", "costMs", "reason", "result"})

    # ==================== 汇总 ====================
    print("\n" + "=" * 72)
    if failed:
        print("✗ %d 项通过，%d 项失败" % (passed, failed))
        print("\n失败项：")
        for f in failures:
            print("  - " + f)
        return 1
    print("✓ %d 项断言全部通过" % passed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
