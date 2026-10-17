#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
创建一个可登录管理端的 ADMIN 测试账号。

为什么需要这个脚本：数据库 346 个用户全部是 role='USER'，没有管理员账号
就无法登录管理端，PRD 里「列表显示 7508/5651/9602 真实单词数」等验收项
全部做不了。手工改库容易在密码哈希上出错（必须是 BCrypt，不能写明文），
所以固化成可重复执行的脚本。

用法：
    python scripts/create-admin.py                      # 默认创建 admin / Admin@12345
    python scripts/create-admin.py --username boss --password 'MyPass123'
    python scripts/create-admin.py --promote mp_probe01  # 把已有账号提权（不改密码）

可重复执行：账号已存在时只确保 role=ADMIN、status=1、deleted=0，不覆盖密码
（除非显式传 --password 且带 --reset-password）。
"""
import argparse
import io
import os
import sys

try:
    import pymysql
except ImportError:
    sys.exit("需要 pymysql：pip install pymysql")

# 凭据从环境变量读取，禁止硬编码（密码曾经硬编码并泄漏进 Git 历史）。
DB = dict(host=os.environ.get("DB_HOST", "127.0.0.1"),
          user=os.environ.get("DB_USERNAME", "root"),
          password=os.environ.get("DBPASS", os.environ.get("DB_PASSWORD", "")),
          database=os.environ.get("DB_NAME", "ai_vocab"), charset="utf8mb4")

DEFAULT_USERNAME = "admin"
DEFAULT_PASSWORD = "Admin@12345"


def bcrypt_hash(password: str) -> str:
    """
    生成 BCrypt 哈希，强度 10 —— 与后端 AuthServiceImpl.BCRYPT_STRENGTH 一致。

    必须用 BCrypt：后端用 BCryptPasswordEncoder.matches() 校验，
    写明文或别的算法（MD5/SHA）登录时一律返回密码错误。
    """
    try:
        import bcrypt
    except ImportError:
        sys.exit("需要 bcrypt：pip install bcrypt")
    return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt(rounds=10)).decode()


def main() -> int:
    ap = argparse.ArgumentParser(description="创建或提权一个 ADMIN 账号")
    ap.add_argument("--username", default=DEFAULT_USERNAME)
    ap.add_argument("--password", default=DEFAULT_PASSWORD,
                    help="仅新建账号时使用；提权已有账号默认不改密码")
    ap.add_argument("--promote", metavar="EXISTING_USERNAME",
                    help="把已有账号提权为 ADMIN，不新建")
    ap.add_argument("--nickname", default="管理员")
    args = ap.parse_args()

    conn = pymysql.connect(**DB)
    cur = conn.cursor()

    if args.promote:
        cur.execute("SELECT id, role FROM user WHERE username=%s", (args.promote,))
        row = cur.fetchone()
        if not row:
            sys.exit("用户不存在：%s" % args.promote)
        uid, old_role = row
        cur.execute(
            "UPDATE user SET role='ADMIN', status=1, deleted=0 WHERE id=%s", (uid,)
        )
        conn.commit()
        print("✓ 已提权 %s（id=%s）：%s → ADMIN" % (args.promote, uid, old_role))
        print("  密码保持原样，用该账号原有密码登录")
        return 0

    cur.execute("SELECT id, role, status FROM user WHERE username=%s", (args.username,))
    row = cur.fetchone()

    if row:
        uid, role, status = row
        if role == "ADMIN" and status == 1:
            print("✓ %s 已是可用的 ADMIN（id=%s），无需改动" % (args.username, uid))
        else:
            cur.execute(
                "UPDATE user SET role='ADMIN', status=1, deleted=0 WHERE id=%s", (uid,)
            )
            conn.commit()
            print("✓ 已修正 %s（id=%s）：role %s→ADMIN，status %s→1"
                  % (args.username, uid, role, status))
        print("  密码：未改动（如需重置，先删掉该账号或手工改库）")
        return 0

    cur.execute(
        """INSERT INTO user (username, password_hash, nickname, role, status, deleted,
                             create_time, update_time)
           VALUES (%s, %s, %s, 'ADMIN', 1, 0, NOW(), NOW())""",
        (args.username, bcrypt_hash(args.password), args.nickname),
    )
    conn.commit()
    uid = cur.lastrowid
    print("✓ 已创建 ADMIN 账号")
    print("  用户名：%s" % args.username)
    print("  密码：  %s" % args.password)
    print("  id：    %s" % uid)
    print("\n登录地址：http://localhost:5173/login")
    return 0


if __name__ == "__main__":
    sys.exit(main())
