#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
清理冒烟脚本留下的测试数据（幂等、可重复执行）。

背景：g4/g5/stage9 系列的冒烟脚本为了验证真实链路会创建测试用户（前缀
g4_/g4cd_/g4n_/g5_/g5t_/stage9/g6adm_/g6usr_）。脚本自身不删用户（删除需要
登录态），因此测试用户会逐轮累积。本脚本按**前缀白名单**物理清理它们及其
关联数据。

安全：
  * 只删 username 命中白名单前缀的用户（前缀里的 `_` 已转义为字面量，不是通配符）；
  * 只删这些用户的关联行（study_record / mistake_entry / word_progress / user_word_book）；
  * 只删名字以 `G6冒烟`/`G4冒烟` 之类**测试词书前缀**开头的词书及其单词；
  * 绝不触碰其它用户、三本种子词书（CET4/CET6/KAOYAN）、以及红线库 anime_tracker。

用法：
  DBPASS=<密码> python backend/scripts/clean-smoke-data.py [--dry-run]
"""
import os
import sys

try:
    import pymysql
except ImportError:
    print("需要 pymysql：pip install pymysql")
    sys.exit(2)

HOST = os.environ.get("DB_HOST", "127.0.0.1")
PORT = int(os.environ.get("DB_PORT", "3306"))
USER = os.environ.get("DB_USERNAME", "root")
NAME = os.environ.get("DB_NAME", "ai_vocab")
PASSWORD = os.environ.get("DBPASS", os.environ.get("DB_PASSWORD", ""))

# 用户前缀白名单（与各冒烟脚本一致）
USER_PREFIXES = ["g4_", "g4cd_", "g4n_", "g5_", "g5t_", "stage9", "g6adm_", "g6usr_"]
# 测试词书名前缀白名单（冒烟脚本建的书名）
BOOK_PREFIXES = ["G6冒烟", "G4冒烟", "冒烟"]

DRY = "--dry-run" in sys.argv


def like(prefix):
    """构造只匹配该前缀字面量的 LIKE 模式（转义 _ 与 %）。"""
    esc = prefix.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%")
    return esc + "%"


def where_users(col="username"):
    return " OR ".join("%s LIKE %%s" % col for _ in USER_PREFIXES), [like(p) for p in USER_PREFIXES]


def main():
    conn = pymysql.connect(host=HOST, port=PORT, user=USER, password=PASSWORD,
                           database=NAME, charset="utf8mb4", autocommit=True)
    cur = conn.cursor()

    # ---- 目标用户
    cond, args = where_users()
    cur.execute("SELECT id, username FROM user WHERE " + cond, args)
    users = cur.fetchall()
    uids = [u[0] for u in users]
    print("命中测试用户 %d 个" % len(users))
    for uid, uname in users[:10]:
        print("   ", uname)
    if len(users) > 10:
        print("    ... 另有 %d 个" % (len(users) - 10))

    # ---- 目标词书（连带其单词）
    bcond = " OR ".join("name LIKE %s" for _ in BOOK_PREFIXES)
    bargs = [like(p) for p in BOOK_PREFIXES]
    cur.execute("SELECT id, name FROM word_book WHERE deleted = 0 AND (" + bcond + ")", bargs)
    books = cur.fetchall()
    bids = [b[0] for b in books]
    print("命中测试词书 %d 本" % len(books))
    for bid, bname in books:
        print("    id=%s %s" % (bid, bname))

    if DRY:
        print("\n--dry-run：未做任何删除")
        conn.close()
        return

    if uids:
        ph = ",".join(["%s"] * len(uids))
        for table in ("study_record", "mistake_entry", "word_progress", "user_word_book"):
            cur.execute("DELETE FROM %s WHERE user_id IN (%s)" % (table, ph), uids)
            print("  删除 %-16s %d 行" % (table, cur.rowcount))
        cur.execute("DELETE FROM user WHERE id IN (%s)" % ph, uids)
        print("  删除 %-16s %d 行" % ("user", cur.rowcount))

    if bids:
        ph = ",".join(["%s"] * len(bids))
        # word_book 用逻辑删除（保留可追溯），word 物理删除（与导入脚本的清理口径一致）
        cur.execute("DELETE FROM word WHERE book_id IN (%s)" % ph, bids)
        print("  删除 %-16s %d 行" % ("word", cur.rowcount))
        cur.execute("UPDATE word_book SET deleted = 1 WHERE id IN (%s)" % ph, bids)
        print("  逻辑删 %-14s %d 行" % ("word_book", cur.rowcount))

    # ---- 复核：种子数据不受影响
    cur.execute("SELECT COUNT(*) FROM word_book WHERE deleted = 0")
    print("\n复核：未删除词书 %d 本（应为 3）" % cur.fetchone()[0])
    cur.execute("SELECT COUNT(*) FROM word")
    print("复核：word 总数 %d（应为 22761）" % cur.fetchone()[0])
    conn.close()
    print("清理完成 ✅")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
