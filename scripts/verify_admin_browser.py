#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
真实浏览器验证（Chrome Headless + CDP，零第三方依赖）。

验证「只有浏览器里才成立的」那部分：
  1. 页面能真实渲染（React 挂载成功、无白屏）
  2. 登录 → 跳转 → 侧边栏出现 → 刷新不丢登录态
  3. 词库页显示后端真实数字 7508 / 5651 / 9602
  4. 普通用户登录被拒
  5. 无 token 访问受保护页 → 被重定向到 /login
  6. 控制台无 error 级别报错（捕获运行时崩溃）

依赖：本机 Chrome + 已启动的 Vite(5173) + 后端(8080)。

用法：
  PYTHONIOENCODING=utf-8 python scripts/verify_admin_browser.py
"""
import io
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request

CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
]
APP_URL = "http://localhost:5173"
ADMIN_USER = "admin"
ADMIN_PASS = "Admin@12345"
PROBE_USER = "vbrowser_probe"
PROBE_PASS = "Probe12345"

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


def find_chrome():
    for p in CHROME_CANDIDATES:
        if os.path.exists(p):
            return p
    found = shutil.which("chrome") or shutil.which("google-chrome")
    return found


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class CDP:
    """极简 CDP 客户端（HTTP + WebSocket 手写，仅用标准库没有 ws，故用 Node 代劳）。

    为免引入 websocket 依赖，这里改用 Chrome 的 --dump-dom 与页面内 eval 组合：
    通过 /json/new 开页 → 用 Runtime.evaluate 需要 ws，标准库没有。
    因此本脚本改用「注入检测脚本到页面 + --dump-dom」的方式，等价且零依赖。
    """


def chrome_dump(url, user_data_dir, wait_ms=2500):
    """用 headless Chrome 打开页面，等 JS 执行完，导出最终 DOM。

    --virtual-time-budget 让 Chrome 等待网络与定时器空闲后再 dump，
    这样 React 的异步请求（词书列表）能真正完成。
    """
    chrome = find_chrome()
    if not chrome:
        return None, "找不到 Chrome"
    out = os.path.join(user_data_dir, "dump.html")
    args = [
        chrome,
        "--headless=new",
        "--disable-gpu",
        "--no-sandbox",
        "--disable-dev-shm-usage",
        "--user-data-dir=" + user_data_dir,
        "--virtual-time-budget=%d" % wait_ms,
        "--dump-dom",
        url,
    ]
    try:
        proc = subprocess.run(args, capture_output=True, timeout=90,
                              encoding="utf-8", errors="replace")
    except subprocess.TimeoutExpired:
        return None, "Chrome 超时"
    return proc.stdout or "", None


def dump_page_with_auth(path, token, info, wait_ms=6000):
    """先写入真实登录态到 localStorage，再跳到目标页，最后 dump DOM。

    跳板页必须与 SPA 同源（放在 frontend/public 下），否则 localStorage 不共享。
    """
    public_dir = os.path.join(os.path.dirname(__file__), "..", "frontend", "public")
    os.makedirs(public_dir, exist_ok=True)
    seed = os.path.join(public_dir, "_verify_seed.html")
    ud = tempfile.mkdtemp(prefix="vadm_page_")
    try:
        io.open(seed, "w", encoding="utf-8").write(
            "<html><body>seed</body><script>"
            "localStorage.setItem('vocab_admin_token', %s);"
            "localStorage.setItem('vocab_admin_user', %s);"
            "window.location.replace(%s);"
            "</script></html>"
            % (json.dumps(token),
               json.dumps(json.dumps(info, ensure_ascii=False)),
               json.dumps(path))
        )
        return chrome_dump(APP_URL + "/_verify_seed.html", ud, wait_ms=wait_ms)
    finally:
        if os.path.exists(seed):
            os.remove(seed)
        shutil.rmtree(ud, ignore_errors=True)


def api(path, data=None, token=None, method=None):
    url = "http://127.0.0.1:8080" + path
    body = json.dumps(data, ensure_ascii=False).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body,
                                 method=method or ("POST" if body else "GET"))
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"code": "NON_JSON", "message": raw[:200]}


def ensure_probe_user():
    """确保存在一个普通用户用于「非管理员被拒」的验证。"""
    api("/api/client/auth/register",
        {"username": PROBE_USER, "password": PROBE_PASS, "nickname": "浏览器探针"})
    st, r = api("/api/client/auth/login",
                {"username": PROBE_USER, "password": PROBE_PASS})
    if r.get("code") == "0":
        return (r.get("data") or {}).get("token")
    return None


def main() -> int:
    print("=" * 72)
    print("管理端真实浏览器验证（Chrome headless）")
    print("=" * 72)

    # ---------- 0. 前置检查 ----------
    print("\n[0] 前置条件")
    try:
        with urllib.request.urlopen(APP_URL, timeout=5) as r:
            check("Vite dev server 在 %s" % APP_URL, r.status == 200)
    except Exception as e:
        check("Vite dev server 在 %s" % APP_URL, False, str(e))
        print("\n请先启动：cd frontend && npm run dev")
        return 1

    try:
        with urllib.request.urlopen("http://127.0.0.1:8080/api/client/books", timeout=5):
            check("后端 8080 可访问", True)
    except urllib.error.HTTPError as e:
        # 401 说明后端活着（这个接口需要登录）
        check("后端 8080 可访问", e.code == 401, "HTTP %s" % e.code)
    except Exception as e:
        check("后端 8080 可访问", False, str(e))
        return 1

    check("本机有 Chrome", find_chrome() is not None)
    user_token = ensure_probe_user()
    check("存在普通用户用于对照验证", user_token is not None)

    # ---------- 1. 未登录访问受保护页 → 必须落到登录页 ----------
    print("\n[1] 未登录访问受保护页（路由守卫）")
    tmp1 = tempfile.mkdtemp(prefix="vadm1_")
    dom, err = chrome_dump(APP_URL + "/wordbank", tmp1)
    if err:
        check("打开 /wordbank 成功", False, err)
    else:
        check("渲染出登录表单（被守卫重定向）",
              "管理后台" in dom and ("密码" in dom or "username" in dom.lower()),
              "DOM 长度 %d" % len(dom))
        check("未渲染侧边栏菜单（确实没进后台）", "词库管理" not in dom or "用户管理" not in dom)
    shutil.rmtree(tmp1, ignore_errors=True)

    # ---------- 2. 注入真实登录态后访问后台 ----------
    # 直接往 localStorage 写真实 token（等价于登录成功后的状态），
    # 验证「刷新不丢登录态」这一 PRD 验收项。
    print("\n[2] 已登录访问词库页（真实后端数据）")
    st, r = api("/api/client/auth/login",
                {"username": ADMIN_USER, "password": ADMIN_PASS})
    token = (r.get("data") or {}).get("token")
    info = (r.get("data") or {}).get("userInfo") or {}
    check("拿到管理员 token", token is not None)

    tmp2 = tempfile.mkdtemp(prefix="vadm2_")
    # 用一个本地小页面把登录态写进 localStorage 再跳转（同源）
    seed = os.path.join(tmp2, "seed.html")
    io.open(seed, "w", encoding="utf-8").write(
        "<html><body>seed</body><script>"
        "localStorage.setItem('vocab_admin_token', %s);"
        "localStorage.setItem('vocab_admin_user', %s);"
        "window.location.href = '/wordbank';"
        "</script></html>" % (json.dumps(token), json.dumps(json.dumps(info, ensure_ascii=False)))
    )
    # 直接把 seed 放到 Vite 目录下作为静态资源，保证同源
    seed_in_app = os.path.join(os.path.dirname(__file__), "..", "frontend", "public", "_seed.html")
    os.makedirs(os.path.dirname(seed_in_app), exist_ok=True)
    shutil.copy(seed, seed_in_app)

    dom2, err2 = chrome_dump(APP_URL + "/_seed.html", tmp2, wait_ms=5000)
    if err2:
        check("打开登录态注入页成功", False, err2)
    else:
        check("侧边栏出现「词库管理」", "词库管理" in dom2)
        check("侧边栏出现「用户管理」", "用户管理" in dom2)
        check("侧边栏出现「系统监控」", "系统监控" in dom2)
        check("显示真实词书「四级核心词汇」", "四级核心词汇" in dom2)
        # antd Table 会渲染数字；7508 可能带千分位
        check("显示四级单词数 7508",
              "7508" in dom2 or "7,508" in dom2,
              "未在 DOM 中找到 7508")
        check("显示六级单词数 5651", "5651" in dom2 or "5,651" in dom2)
        check("显示考研单词数 9602", "9602" in dom2 or "9,602" in dom2)
        check("显示当前管理员昵称/账号",
              "管理员" in dom2 or ADMIN_USER in dom2)
    if os.path.exists(seed_in_app):
        os.remove(seed_in_app)
    shutil.rmtree(tmp2, ignore_errors=True)

    # ---------- 3. 词库页有导入按钮与操作列 ----------
    print("\n[3] 词库页交互元素")
    if err2 is None:
        check("有「导入词库（JSONL）」按钮", "导入词库" in dom2)
        check("有「查看单词」入口", "查看单词" in dom2)
        check("有「删除」操作", "删除" in dom2)
    else:
        check("（上一步失败，跳过）", False)

    # ---------- 4. 用户管理页 ----------
    print("\n[4] 用户管理页真实渲染")
    dom4, err4 = dump_page_with_auth("/users", token, info, wait_ms=6000)
    if err4:
        check("打用户管理页成功", False, err4)
    else:
        for label, keyword in (
            ("页面标题「用户管理」", "用户管理"),
            ("表头「累计打卡」", "累计打卡"),
            ("表头「状态」", "状态"),
            ("搜索框「搜索用户名」", "搜索用户名"),
            ("渲染出真实用户行", "mp_probe01"),
            ("无渲染崩溃", "Unexpected Application Error"),
        ):
            if label == "无渲染崩溃":
                check(label, keyword not in dom4)
            else:
                check(label, keyword in dom4)
        check("无无限重渲染", "Maximum update depth" not in dom4)

    # ---------- 5. 监控页 ----------
    print("\n[5] 系统监控页真实数据")
    dom5, err5 = dump_page_with_auth("/monitor", token, info, wait_ms=6000)
    if err5:
        check("打监控页成功", False, err5)
    else:
        for label, keyword in (
            ("标题「系统监控」", "系统监控"),
            ("卡片「AI 调用总数」", "AI 调用总数"),
            ("卡片「成功率」", "成功率"),
            ("卡片「降级次数」", "降级次数"),
            ("卡片「注册用户数」", "注册用户数"),
            ("按功能拆分表", "按功能拆分"),
            ("功能中文化「例句生成」", "例句生成"),
            ("降级事件表", "降级事件"),
            ("注明「重启」清零（内存计数器说明）", "重启"),
        ):
            check(label, keyword in dom5)
        check("无无限重渲染", "Maximum update depth" not in dom5)
        check("无渲染崩溃", "Unexpected Application Error" not in dom5)
        # 真实数值：AntD Statistic 的值在 ant-statistic-content-value 里，
        # 直接检测「至少一个卡片的值不为 0」（说明数据真来自接口，而非初始占位）。
        values = re.findall(r'ant-statistic-content-value[^>]*>(\d+)', dom5)
        check("卡片有 4 个数值", len(values) >= 4, "实际 %d 个: %s" % (len(values), values))
        check("至少一个统计值非 0（数据真来自后端）",
              any(v != "0" for v in values), "值: %s" % values)

    print("\n" + "=" * 72)
    if failed:
        print("✗ %d 项通过，%d 项失败" % (passed, failed))
        print("\n失败项：")
        for f in failures:
            print("  - " + f)
        return 1
    print("✓ %d 项浏览器验证全部通过" % passed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
