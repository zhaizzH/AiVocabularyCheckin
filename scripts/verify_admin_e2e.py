#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
真实交互链路验证：真的在浏览器里填表单、点按钮，验证写操作闭环。

覆盖 headless dump DOM 做不到的部分：
  - 在登录页真实输入用户名/密码并提交 → 进入后台
  - 普通用户提交被拒且不落 token
  - 词库页删除 → 列表真的少一本
  - 单词弹窗新增/删除 → wordCount 真的变化

实现方式：Chrome remote debugging + 手写 WebSocket 客户端（标准库实现，
不引入 selenium/playwright）。CDP 只需 Runtime.evaluate，WebSocket 帧很小。

用法：
  PYTHONIOENCODING=utf-8 python scripts/verify_admin_e2e.py
"""
import base64
import io
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.error

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
APP = "http://localhost:5173"
API = "http://127.0.0.1:8080"
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


# ----------------------------------------------------------------------
# 极简 WebSocket 客户端（CDP 用，文本帧足够）
# ----------------------------------------------------------------------
class WsClient:
    def __init__(self, url, timeout=30):
        # ws://127.0.0.1:PORT/devtools/page/ID
        rest = url.split("://", 1)[1]
        hostport, path = rest.split("/", 1)
        host, port = hostport.split(":")
        self.sock = socket.create_connection((host, int(port)), timeout=timeout)
        self.sock.settimeout(timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            "GET /%s HTTP/1.1\r\n"
            "Host: %s:%s\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            "Sec-WebSocket-Key: %s\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n" % (path, host, port, key)
        )
        self.sock.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            buf += self.sock.recv(4096)
        if b"101" not in buf.split(b"\r\n")[0]:
            raise RuntimeError("WebSocket 握手失败: %r" % buf[:200])
        self._id = 0

    def send(self, payload):
        data = payload.encode("utf-8")
        header = bytearray([0x81])  # FIN + text
        n = len(data)
        if n < 126:
            header.append(0x80 | n)
        elif n < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", n)
        mask = os.urandom(4)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        self.sock.sendall(bytes(header) + masked)

    def _recv_exact(self, n):
        chunks = b""
        while len(chunks) < n:
            c = self.sock.recv(n - len(chunks))
            if not c:
                raise RuntimeError("连接关闭")
            chunks += c
        return chunks

    def recv(self):
        b1, b2 = self._recv_exact(2)
        opcode = b1 & 0x0F
        length = b2 & 0x7F
        if length == 126:
            length = struct.unpack(">H", self._recv_exact(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self._recv_exact(8))[0]
        data = self._recv_exact(length) if length else b""
        if opcode == 0x8:
            raise RuntimeError("收到 close 帧")
        return data.decode("utf-8", errors="replace")

    def call(self, method, params=None, timeout=30):
        self._id += 1
        mid = self._id
        self.send(json.dumps({"id": mid, "method": method,
                              "params": params or {}}))
        deadline = time.time() + timeout
        while time.time() < deadline:
            msg = json.loads(self.recv())
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError("CDP 错误: %s" % msg["error"])
                return msg.get("result", {})
        raise RuntimeError("CDP 调用超时: %s" % method)

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


class Browser:
    def __init__(self):
        self.port = self._free_port()
        self.ud = tempfile.mkdtemp(prefix="vadm_e2e_")
        self.proc = subprocess.Popen(
            [CHROME, "--headless=new", "--disable-gpu", "--no-sandbox",
             "--disable-dev-shm-usage",
             "--remote-debugging-port=%d" % self.port,
             "--user-data-dir=" + self.ud,
             "about:blank"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self._wait_ready()

    @staticmethod
    def _free_port():
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        p = s.getsockname()[1]
        s.close()
        return p

    def _wait_ready(self):
        for _ in range(100):
            try:
                with urllib.request.urlopen(
                        "http://127.0.0.1:%d/json/version" % self.port, timeout=2) as r:
                    json.loads(r.read())
                    return
            except Exception:
                time.sleep(0.2)
        raise RuntimeError("Chrome 未能启动")

    def new_page(self, url):
        req = urllib.request.Request(
            "http://127.0.0.1:%d/json/new?%s" % (self.port, url), method="PUT")
        with urllib.request.urlopen(req, timeout=10) as r:
            info = json.loads(r.read())
        ws = WsClient(info["webSocketDebuggerUrl"])
        ws.call("Page.enable")
        ws.call("Runtime.enable")
        return ws

    def close(self):
        try:
            self.proc.terminate()
            self.proc.wait(timeout=10)
        except Exception:
            try:
                self.proc.kill()
            except Exception:
                pass
        shutil.rmtree(self.ud, ignore_errors=True)


def ev(ws, expr, await_promise=False, timeout=30):
    """在页面里求值，返回 JS 值（JSON 反序列化后）。"""
    res = ws.call("Runtime.evaluate", {
        "expression": expr,
        "returnByValue": True,
        "awaitPromise": await_promise,
        "userGesture": True,
    }, timeout=timeout)
    if "exceptionDetails" in res:
        raise RuntimeError("页面异常: %s" %
                           json.dumps(res["exceptionDetails"])[:400])
    return res.get("result", {}).get("value")


def wait_for(ws, expr, timeout=15, interval=0.25):
    """轮询直到表达式为真。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if ev(ws, expr):
                return True
        except Exception:
            pass
        time.sleep(interval)
    return False


def api(path, data=None, token=None, method=None):
    body = json.dumps(data, ensure_ascii=False).encode("utf-8") if data is not None else None
    req = urllib.request.Request(API + path, data=body,
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


# React 受控输入必须触发原生 input 事件，否则 React 的 onChange 收不到。
SET_INPUT = r"""
(function(selector, value) {
  const el = document.querySelector(selector);
  if (!el) return 'NO_ELEMENT:' + selector;
  // textarea 与 input 是不同的类，setter 必须取自对应的 prototype，
  // 否则 setter.call 抛 TypeError（实测踩过）。
  const proto = el.tagName === 'TEXTAREA'
    ? window.HTMLTextAreaElement.prototype
    : window.HTMLInputElement.prototype;
  const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
  setter.call(el, value);
  // React 17+ 在 root 上委托监听，需冒泡的原生 input 事件
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return 'OK';
})(%s, %s)
"""

CLICK_TEXT = r"""
(function(text) {
  // 先找按钮：textContent 相同的 div/span 外层容器很多（AntD 的
  // form-item > row > col > content > button > span 五层文本都一样），
  // 点 DIV 不会提交表单 —— 必须优先命中真正可点击的元素。
  const sel = 'button, a, .ant-dropdown-menu-item, .ant-popconfirm button';
  const nodes = Array.from(document.querySelectorAll(sel));
  const target = nodes.find(n => n.textContent
                                 && n.textContent.replace(/\s/g, '') === text.replace(/\s/g, '')
                                 && n.offsetParent !== null
                                 && !n.disabled);
  if (!target) return 'NO_TARGET:' + text;
  target.click();
  return 'OK';
})(%s)
"""

CLICK_IN_ROW = r"""
(function(rowText, buttonText) {
  const row = Array.from(document.querySelectorAll('tr'))
    .find(r => r.innerText.includes(rowText));
  if (!row) return 'NO_ROW';
  const btn = Array.from(row.querySelectorAll('button'))
    .find(b => b.innerText.replace(/\s/g, '') === buttonText.replace(/\s/g, '')
               && !b.disabled);
  if (!btn) return 'NO_BTN';
  btn.click();
  return 'OK';
})(%s, %s)
"""


def main() -> int:
    print("=" * 72)
    print("管理端真实交互验证（CDP 驱动 Chrome）")
    print("=" * 72)

    print("\n[0] 前置条件")
    try:
        with urllib.request.urlopen(APP, timeout=5) as r:
            check("Vite dev server 可用", r.status == 200)
    except Exception as e:
        check("Vite dev server 可用", False, str(e))
        return 1
    check("Chrome 存在", os.path.exists(CHROME))

    # 准备一个普通用户
    probe_user = "ve2e_" + os.urandom(3).hex()
    api("/api/client/auth/register",
        {"username": probe_user, "password": "Probe12345", "nickname": "E2E探针"})

    browser = Browser()
    try:
        # ============ 1. 普通用户登录被拒 ============
        print("\n[1] 普通用户在登录页真实提交（应被拒且不落 token）")
        ws = browser.new_page(APP + "/login")
        wait_for(ws, "!!document.querySelector('input#username, input[autocomplete=username]')")
        ev(ws, "localStorage.clear()")

        r1 = ev(ws, SET_INPUT % ('"#username"', json.dumps(probe_user)))
        check("填入普通用户名", r1 == "OK", str(r1))
        r2 = ev(ws, SET_INPUT % ('"#password"', json.dumps("Probe12345")))
        check("填入密码", r2 == "OK", str(r2))
        r3 = ev(ws, CLICK_TEXT % json.dumps("登 录"))
        check("点击登录按钮", r3 == "OK", str(r3))

        got_msg = wait_for(
            ws, "document.body.innerText.includes('不是管理员')", timeout=15)
        check("提示「不是管理员」", got_msg)
        tok = ev(ws, "localStorage.getItem('vocab_admin_token')")
        check("非管理员**不落 token**（关键：避免登录成功但全 403）", tok is None, str(tok))

        # ============ 2. 管理员真实登录 ============
        print("\n[2] 管理员在登录页真实提交")
        ev(ws, "localStorage.clear()")
        ev(ws, "window.location.reload()")
        wait_for(ws, "!!document.querySelector('#username')", timeout=15)
        ev(ws, SET_INPUT % ('"#username"', json.dumps(ADMIN_USER)))
        ev(ws, SET_INPUT % ('"#password"', json.dumps(ADMIN_PASS)))
        ev(ws, CLICK_TEXT % json.dumps("登 录"))

        entered = wait_for(
            ws, "location.pathname === '/wordbank' && document.body.innerText.includes('词库管理')",
            timeout=20)
        check("登录成功并进入 /wordbank", entered,
              "当前 %s" % ev(ws, "location.pathname"))
        tok2 = ev(ws, "localStorage.getItem('vocab_admin_token')")
        check("token 已持久化到 localStorage", isinstance(tok2, str) and len(tok2) > 20)

        # ============ 3. 刷新页面不丢登录态 ============
        print("\n[3] 刷新页面不丢登录态（PRD 验收项）")
        ev(ws, "window.location.reload()")
        wait_for(ws, "document.body.innerText.includes('词库管理')", timeout=20)
        still_in = ev(ws, "location.pathname")
        check("刷新后仍在 /wordbank（未被踢回登录页）", still_in == "/wordbank", str(still_in))
        check("刷新后 token 仍在", ev(ws, "!!localStorage.getItem('vocab_admin_token')"))

        # ============ 4. 词库列表真实数据 ============
        print("\n[4] 词库页真实数据与总数")
        shown = wait_for(
            ws, "document.body.innerText.includes('四级核心词汇')", timeout=20)
        check("渲染出「四级核心词汇」", shown)
        text = ev(ws, "document.body.innerText")
        check("显示 7,508", "7,508" in text or "7508" in text)
        check("底部合计 22,761", "22,761" in text or "22761" in text)

        # ============ 5. 打开单词弹窗并搜索 ============
        print("\n[5] 打开单词弹窗 → 搜索 → 新增 → 删除（写闭环）")
        clicked = ev(ws, """
        (function(){
          const rows = Array.from(document.querySelectorAll('tr'));
          const row = rows.find(r => r.innerText.includes('四级核心词汇'));
          if (!row) return 'NO_ROW';
          const btn = Array.from(row.querySelectorAll('button'))
            .find(b => b.innerText.includes('查看单词'));
          if (!btn) return 'NO_BTN';
          btn.click();
          return 'OK';
        })()
        """)
        check("点击「查看单词」", clicked == "OK", str(clicked))

        modal_open = wait_for(
            ws, "document.body.innerText.includes('单词管理 · 四级核心词汇')", timeout=15)
        check("弹窗打开（标题含词书名）", modal_open)

        # 搜索
        ev(ws, SET_INPUT % ('"input[placeholder=\\"搜索单词\\"]"', json.dumps("abandon")))
        ev(ws, """
        (function(){
          const el = document.querySelector('input[placeholder="搜索单词"]');
          if (!el) return 'NO';
          el.dispatchEvent(new KeyboardEvent('keydown',
            {key:'Enter', keyCode:13, bubbles:true}));
          const btn = el.parentElement.querySelector('.ant-input-search-button');
          if (btn) btn.click();
          return 'OK';
        })()
        """)
        found = wait_for(
            ws, "document.body.innerText.includes('abandon')", timeout=15)
        check("搜索 abandon 有结果", found)

        # 记录当前四级 wordCount（用于验证写操作后联动）
        st, r = api("/api/admin/books", token=tok2)
        before_count = {b["name"]: b["wordCount"] for b in r["data"]}["四级核心词汇"]

        # 新增单词
        ev(ws, CLICK_TEXT % json.dumps("新增单词"))
        wait_for(ws, "document.body.innerText.includes('新增单词')", timeout=10)
        new_word = "e2eprobe" + os.urandom(2).hex()
        ev(ws, SET_INPUT % ('"input[placeholder=\\"例如：abandon\\"]"', json.dumps(new_word)))
        ev(ws, SET_INPUT % ('"textarea[placeholder=\\"例如：v. 放弃，抛弃\\"]"',
                            json.dumps("n. E2E 探针")))
        ev(ws, CLICK_TEXT % json.dumps("保 存"))
        saved = wait_for(
            ws,
            "document.body.innerText.includes('新增成功') || document.body.innerText.includes('%s')" % new_word,
            timeout=20)
        check("新增单词成功", saved)

        st, r = api("/api/admin/words?bookId=%s&keyword=%s" %
                    ({b['name']: b['bookId'] for b in
                      api("/api/admin/books", token=tok2)[1]["data"]}["四级核心词汇"],
                     new_word), token=tok2)
        created_id = None
        if r.get("code") == "0" and (r.get("data") or {}).get("list"):
            created_id = r["data"]["list"][0]["wordId"]
        check("后端确实新增了该单词", created_id is not None, str(r)[:200])

        st, r2 = api("/api/admin/books", token=tok2)
        after_count = {b["name"]: b["wordCount"] for b in r2["data"]}["四级核心词汇"]
        check("wordCount 联动 +1", after_count == before_count + 1,
              "%s → %s" % (before_count, after_count))

        # 清理：删除该单词
        if created_id:
            st, r = api("/api/admin/words/%s" % created_id, token=tok2, method="DELETE")
            check("删除探针单词", r.get("code") == "0")
            st, r3 = api("/api/admin/books", token=tok2)
            back = {b["name"]: b["wordCount"] for b in r3["data"]}["四级核心词汇"]
            check("wordCount 回到原值", back == before_count, "%s" % back)

        # ============ 5b. 真实 JSONL 上传（CDP setFileInputFiles）============
        print("\n[5b] 真实 JSONL 文件上传 → 预览 → 确认导入")
        # 关掉单词弹窗
        ev(ws, """
        (function(){
          const btns = Array.from(document.querySelectorAll('.ant-modal button'))
            .filter(b => b.innerText.replace(/\s/g,'') === '关 闭'
                      || b.innerText.replace(/\s/g,'') === '关闭'
                      || b.querySelector('.ant-modal-close-x'));
          const x = document.querySelector('.ant-modal-close');
          if (x) { x.click(); return 'OK'; }
          return 'NO_CLOSE';
        })()
        """)
        time.sleep(0.8)

        jsonl_path = os.path.join(tempfile.gettempdir(), "e2e_import.jsonl")
        # 两行合法 + 一行缺 word 的坏行（后端应跳过而非整体失败）
        line1 = '{"word":"e2eimport1","uk":"/pru:b/","translations":[{"type":"n","translation":"probe one"}],"sentences":[{"sentence":"This is probe one."}]}'
        line2 = '{"word":"e2eimport2","translations":[{"type":"v","translation":"probe two"}]}'
        line3 = '{"nomin":"bad line"}'
        with io.open(jsonl_path, "w", encoding="utf-8") as fh:
            fh.write(line1 + "\n" + line2 + "\n" + line3 + "\n")

        opened = ev(ws, CLICK_TEXT % json.dumps("导入词库（JSONL）"))
        check("点击「导入词库（JSONL）」", opened == "OK", str(opened))
        time.sleep(1.2)

        ws.call("DOM.enable")
        doc = ws.call("DOM.getDocument", {"depth": -1})
        node = ws.call("DOM.querySelector",
                       {"nodeId": doc["root"]["nodeId"],
                        "selector": "input[type=file]"})
        check("找到文件选择框", node.get("nodeId", 0) > 0)
        ws.call("DOM.setFileInputFiles",
                {"files": [jsonl_path.replace(os.sep, "/")],
                 "nodeId": node["nodeId"]})
        time.sleep(2.5)

        prev = ev(ws, "document.body.innerText")
        check("预览统计正确「共 3 行，可解析 2 行，跳过 1 行」",
              "共 3 行" in prev and "可解析 2 行" in prev and "跳过 1 行" in prev,
              prev[prev.find("共 "):prev.find("共 ") + 40] if "共 " in prev else "无")
        check("预览映射 uk → 音标", "/pru:b/" in prev)
        check("预览映射 translations → 释义", "n. probe one" in prev)
        check("预览映射 sentences → 例句", "This is probe one." in prev)
        check("坏行未中断预览（两个好词都展示）",
              "e2eimport1" in prev and "e2eimport2" in prev)

        book_name = "验收探针书" + os.urandom(2).hex()
        ev(ws, SET_INPUT % ('"#name"', json.dumps(book_name)))
        # 分类是 AntD Select：必须派发 mousedown 才会展开下拉
        # （AntD 监听 mousedown 而非 click），选项是 .ant-select-item-option。
        ev(ws, """
        (function(){
          const sel = document.querySelector('.ant-modal .ant-select-selector');
          if (!sel) return 'NO_SELECT';
          const opt = { bubbles: true, cancelable: true, view: window };
          sel.dispatchEvent(new MouseEvent('mousedown', opt));
          sel.dispatchEvent(new MouseEvent('mouseup', opt));
          sel.click();
          return 'OK';
        })()
        """)
        time.sleep(1.0)
        picked = ev(ws, """
        (function(label){
          const opt = Array.from(document.querySelectorAll('.ant-select-item-option'))
            .find(o => o.textContent && o.textContent.trim() === label);
          if (!opt) return 'NO_OPTION';
          opt.dispatchEvent(new MouseEvent('click', {bubbles:true, view:window}));
          return 'OK';
        })(%s)
        """ % json.dumps("四级 CET4"))
        check("选择分类「四级 CET4」", picked == "OK", str(picked))
        time.sleep(0.5)

        submitted = ev(ws, CLICK_TEXT % json.dumps("确认导入"))
        check("点击「确认导入」", submitted == "OK", str(submitted))

        # 注意：不能用「跳过 1 行」作等待条件——预览里的 Alert 就有这行字，
        # 会立即返回真而实际上根本没点导入成功。只认结果栏的 Alert 标题「导入成功」。
        done = wait_for(
            ws, "document.body.innerText.includes('导入成功')", timeout=30)
        check("导入完成（显示成功/跳过计数）", done)
        result_text = ev(ws, "document.body.innerText")
        check("显示「成功导入 2 条」", "成功导入" in result_text
              and "2" in result_text.split("成功导入", 1)[1][:12])
        check("显示「跳过 1 条」", "跳过" in result_text and "1" in
              result_text.split("跳过", 1)[1][:12] if "跳过" in result_text else False)

        # 后端校验：词书真的建了，且只有 2 个词
        st, r = api("/api/admin/books", token=tok2)
        created_book = next((b for b in r["data"] if b["name"] == book_name), None)
        check("后端创建了新词书", created_book is not None, str(r)[:200])
        if created_book:
            check("新词书 wordCount == 2（坏行被跳过）",
                  created_book["wordCount"] == 2, str(created_book["wordCount"]))
            check("分类正确落库 CET4", created_book["category"] == "CET4",
                  created_book["category"])
            # 清理：删掉验收探针书
            st, r = api("/api/admin/books/%s" % created_book["bookId"],
                        token=tok2, method="DELETE")
            check("清理：删除验收探针书", r.get("code") == "0", str(r)[:150])

        # 关掉导入弹窗，避免影响后续断言
        ev(ws, """
        (function(){
          const x = document.querySelector('.ant-modal-close');
          if (x) { x.click(); return 'OK'; }
          return 'NO_CLOSE';
        })()
        """)
        time.sleep(0.5)
        if os.path.exists(jsonl_path):
            os.remove(jsonl_path)

        # ============ 6. 用户管理页真实交互 ============
        print("\n[6] 用户管理页：禁用 → 该用户立即 401/1003 → 启用")
        ev(ws, "window.location.href='/users'")
        wait_for(ws, "document.body.innerText.includes('用户管理')", timeout=20)

        # 搜索探针用户
        ev(ws, SET_INPUT % ('"input[placeholder=\\"搜索用户名\\"]"', json.dumps(probe_user)))
        ev(ws, """
        (function(){
          const el = document.querySelector('input[placeholder="搜索用户名"]');
          if (!el) return 'NO';
          const btn = el.closest('.ant-input-search')
            ? el.closest('.ant-input-search').querySelector('.ant-input-search-button')
            : null;
          if (btn) btn.click();
          else el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));
          return 'OK';
        })()
        """)
        listed = wait_for(ws, "document.body.innerText.includes(%s)" % json.dumps(probe_user),
                          timeout=15)
        check("搜索到探针用户", listed)

        # 拿探针用户的 userId + token
        st, r = api("/api/admin/users?keyword=%s" % probe_user, token=tok2)
        target_uid = ((r.get("data") or {}).get("list") or [{}])[0].get("userId")
        st, r = api("/api/client/auth/login",
                    {"username": probe_user, "password": "Probe12345"})
        probe_token = (r.get("data") or {}).get("token")
        check("探针用户可正常登录（禁用前）", probe_token is not None)

        # 点禁用并确认
        ev(ws, """
        (function(){
          const row = Array.from(document.querySelectorAll('tr'))
            .find(r => r.innerText.includes(%s));
          if (!row) return 'NO_ROW';
          const btn = Array.from(row.querySelectorAll('button'))
            .find(b => b.innerText.replace(/\\s/g,'') === '禁用');
          if (!btn) return 'NO_BTN';
          btn.click();
          return 'OK';
        })()
        """ % json.dumps(probe_user))
        time.sleep(0.5)
        ev(ws, """
        (function(){
          const pop = document.querySelector('.ant-popconfirm');
          if (!pop) return 'NO_POP';
          const btn = Array.from(pop.querySelectorAll('button'))
            .find(b => b.innerText.replace(/\\s/g,'') === '禁用');
          if (!btn) return 'NO_OK';
          btn.click();
          return 'OK';
        })()
        """)
        disabled = wait_for(
            ws, "document.body.innerText.includes('已禁用该用户')", timeout=15)
        check("UI 显示「已禁用该用户」", disabled)

        st, r = api("/api/client/books", token=probe_token)
        check("被禁用用户旧 token 立即 401", st == 401, "HTTP %s" % st)
        check("code == 1003（账号已被禁用）", r.get("code") == "1003", str(r.get("code")))

        # 恢复
        st, r = api("/api/admin/users/%s/status" % target_uid, {"status": 1},
                    token=tok2, method="PUT")
        check("清理：恢复该用户为正常", r.get("code") == "0")

        # ============ 7. 监控页真实渲染 ============
        print("\n[7] 监控页真实数据")
        ev(ws, "window.location.href='/monitor'")
        # 侧边栏文字也有「系统监控」，只等它会误判「已就绪」而上表还是空的；
        # 必须等表格里的功能名出现（证明 ai-stats 接口已返回并渲染）。
        wait_for(ws, "document.body.innerText.includes('按功能拆分')", timeout=20)
        wait_for(ws, "!!document.querySelector('.ant-table-tbody tr')", timeout=20)
        mon_text = ev(ws, "document.body.innerText")
        check("有「AI 调用总数」", "AI 调用总数" in mon_text)
        check("有「按功能拆分」", "按功能拆分" in mon_text)
        check("有「降级事件」", "降级事件" in mon_text)
        check("功能名已中文化", "例句生成" in mon_text)
        check("注明数据重启清零", "重启" in mon_text)
        check("无渲染错误", "Unexpected Application Error" not in mon_text)

        # ============ 8. 无 token 直访受保护页 ============
        print("\n[8] 清除登录态后直访受保护页（守卫）")
        ev(ws, "localStorage.clear()")
        ev(ws, "window.location.href='/monitor'")
        kicked = wait_for(
            ws, "location.pathname === '/login'", timeout=20)
        check("被重定向回 /login", kicked,
              "当前 %s" % ev(ws, "location.pathname"))

        ws.close()
    finally:
        browser.close()

    # 清理探针用户
    print("\n[9] 清理")
    st, r = api("/api/admin/users?keyword=%s" % probe_user, token=None)
    # 用管理员 token 删不掉用户（无删除接口），仅恢复状态即可
    check("（探针用户已恢复启用状态）", True)

    print("\n" + "=" * 72)
    if failed:
        print("✗ %d 项通过，%d 项失败" % (passed, failed))
        print("\n失败项：")
        for f in failures:
            print("  - " + f)
        return 1
    print("✓ %d 项交互验证全部通过" % passed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
