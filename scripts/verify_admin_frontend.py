#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
前端行为验证：直接执行 frontend/src 里的真实 TypeScript 源码。

不依赖浏览器、不重写逻辑——用 esbuild 把 utils/request.ts + utils/auth.ts +
services/*.ts 打成 CommonJS，在 Node 里提供假 localStorage/fetch 后调用，
断言真实代码的行为（1006/1003/1007 分流、token 注入、Result 解包、路径正确）。

这是「单元测试真实源码」而非「测试复述逻辑」——改坏实现必然变红。

用法：
  PYTHONIOENCODING=utf-8 python scripts/verify_admin_frontend.py
"""
import io
import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
FRONTEND = os.path.join(ROOT, "frontend")
ENTRY = os.path.join(FRONTEND, ".verify_entry.ts")
BUNDLE = os.path.join(FRONTEND, ".verify_bundle.cjs")

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


ENTRY_SOURCE = r"""
// 把要验证的真实源码模块统一导出，供 Node 侧调用。
export * as request from './src/utils/request';
export * as authUtils from './src/utils/auth';
export * as authService from './src/services/auth';
export * as bookService from './src/services/book';
export * as wordService from './src/services/word';
export * as userService from './src/services/user';
export * as monitorService from './src/services/monitor';
export * as format from './src/utils/format';
"""

# Node 侧测试驱动：实现浏览器环境的最小替身，然后调用真实源码
DRIVER = r"""
const assert = (ok, msg) => { if (!ok) throw new Error(msg); };

// ---- 最小 localStorage 替身 ----
const store = new Map();
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
  clear: () => store.clear(),
};

// ---- 最小 window 替身（auth.ts 会 dispatchEvent） ----
let replaceCalls = [];
let events = [];
globalThis.window = {
  location: { pathname: '/wordbank', replace: (u) => { replaceCalls.push(u); } },
  dispatchEvent: (e) => { events.push(e.type); },
  addEventListener: () => {},
  removeEventListener: () => {},
};
globalThis.Event = class { constructor(type) { this.type = type; } };

// ---- 可控的 fetch 替身：axios 在 Node 下用 http adapter，这里直接拦 adapter ----
// 改为拦截 axios 的默认 adapter，按队列返回预置响应。
const axios = require('axios');
let responses = [];
let requests = [];
axios.defaults.adapter = async (config) => {
  requests.push(config);
  const next = responses.shift();
  if (!next) throw new Error('没有预置响应');
  if (next.networkError) {
    const err = new Error('Network Error');
    err.code = 'ERR_NETWORK';
    throw err;
  }
  const resp = {
    data: next.data,
    status: next.status || 200,
    statusText: 'OK',
    headers: {},
    config,
    request: {},
  };
  if (next.status && next.status >= 400) {
    const err = new Error('Request failed');
    err.config = config;
    err.response = resp;
    err.isAxiosError = true;
    throw err;
  }
  return resp;
};

const mod = require('./.verify_bundle.cjs');
const { request, authUtils, authService, bookService, wordService, userService,
        monitorService, format } = mod;

const results = [];
function test(name, fn) {
  try { fn(); results.push([true, name, '']); }
  catch (e) { results.push([false, name, String(e && e.message || e)]); }
}
async function testAsync(name, fn) {
  try { await fn(); results.push([true, name, '']); }
  catch (e) { results.push([false, name, String(e && e.message || e)]); }
}

(async () => {
  // ---------- 1. 登录态读写 ----------
  test('setAuth 后 getToken/getUserInfo 可读', () => {
    authUtils.setAuth('tok-abc', { userId: 1, username: 'admin',
      nickname: '管理员', avatar: null, role: 'ADMIN' });
    assert(authUtils.getToken() === 'tok-abc', 'token 不对');
    assert(authUtils.getUserInfo()?.role === 'ADMIN', 'role 不对');
    assert(authUtils.isAdmin() === true, 'isAdmin 应为 true');
  });

  test('setAuth 触发 auth-changed 事件', () => {
    events = [];
    authUtils.setAuth('t2', { userId: 2, username: 'u', nickname: 'n',
      avatar: null, role: 'ADMIN' });
    assert(events.includes('vocab-admin-auth-changed'), '未派发事件');
  });

  test('普通用户 isAdmin() === false', () => {
    authUtils.setAuth('t3', { userId: 3, username: 'u', nickname: 'n',
      avatar: null, role: 'USER' });
    assert(authUtils.isAdmin() === false, 'USER 不应是 admin');
  });

  test('localStorage 被篡改成非 JSON 时返回 null 不抛错', () => {
    localStorage.setItem('vocab_admin_user', '{{{not json');
    assert(authUtils.getUserInfo() === null, '应返回 null');
  });

  test('localStorage 被篡改成结构不对的 JSON 时返回 null（类型守卫生效）', () => {
    localStorage.setItem('vocab_admin_user', JSON.stringify({ foo: 1 }));
    assert(authUtils.getUserInfo() === null, '应拒绝不合规结构');
  });

  test('clearAuth 清空 token 与用户', () => {
    authUtils.setAuth('t4', { userId: 1, username: 'a', nickname: 'b',
      avatar: null, role: 'ADMIN' });
    authUtils.clearAuth();
    assert(authUtils.getToken() === null, 'token 未清');
    assert(authUtils.getUserInfo() === null, 'user 未清');
  });

  // ---------- 2. 请求层：token 注入 / 解包 ----------
  await testAsync('请求自动注入 Authorization: Bearer', async () => {
    authUtils.setAuth('my-token', { userId: 1, username: 'admin',
      nickname: 'x', avatar: null, role: 'ADMIN' });
    requests = [];
    responses = [{ data: { code: '0', message: 'ok', data: { hello: 1 } } }];
    const out = await request.get('/api/test');
    assert(requests.length === 1, '未发出请求');
    assert(requests[0].headers.Authorization === 'Bearer my-token',
      '实际: ' + requests[0].headers.Authorization);
    assert(out.hello === 1, '未解包 data');
  });

  await testAsync('无 token 时不注入 Authorization 头', async () => {
    authUtils.clearAuth();
    requests = [];
    responses = [{ data: { code: '0', message: 'ok', data: [] } }];
    await request.get('/api/test');
    assert(!requests[0].headers.Authorization, '不应有 Authorization');
  });

  await testAsync('code === "0" 的字符串语义（不是数字 0）', async () => {
    responses = [{ data: { code: '0', message: 'ok', data: 42 } }];
    const out = await request.get('/api/test');
    assert(out === 42, '应返回 42');
  });

  // ---------- 3. 错误分流（最关键的三个码） ----------
  await testAsync('1006：清 token 并跳 /login', async () => {
    authUtils.setAuth('t', { userId: 1, username: 'a', nickname: 'b',
      avatar: null, role: 'ADMIN' });
    replaceCalls = [];
    window.location.pathname = '/wordbank';
    responses = [{ status: 401,
      data: { code: '1006', message: '登录状态已失效', data: null } }];
    let err = null;
    try { await request.get('/api/books'); } catch (e) { err = e; }
    assert(err !== null, '应抛错');
    assert(err.code === '1006', 'code 应为 1006，实际 ' + err.code);
    assert(authUtils.getToken() === null, 'token 应被清空');
    assert(replaceCalls.includes('/login'), '应跳登录页');
  });

  await testAsync('1003（账号禁用）：清 token 并跳 /login', async () => {
    authUtils.setAuth('t', { userId: 1, username: 'a', nickname: 'b',
      avatar: null, role: 'ADMIN' });
    replaceCalls = [];
    window.location.pathname = '/users';
    responses = [{ status: 401,
      data: { code: '1003', message: '账号已被禁用', data: null } }];
    let err = null;
    try { await request.get('/api/books'); } catch (e) { err = e; }
    assert(err.code === '1003', 'code 不对');
    assert(authUtils.getToken() === null, 'token 应被清空');
    assert(replaceCalls.includes('/login'), '应跳登录页');
  });

  await testAsync('1007（非管理员）：跳登录但**不清 token**', async () => {
    authUtils.setAuth('keep-me', { userId: 1, username: 'a', nickname: 'b',
      avatar: null, role: 'ADMIN' });
    replaceCalls = [];
    window.location.pathname = '/monitor';
    responses = [{ status: 403,
      data: { code: '1007', message: '无权访问该资源', data: null } }];
    let err = null;
    try { await request.get('/api/admin/books'); } catch (e) { err = e; }
    assert(err.code === '1007', 'code 不对');
    assert(authUtils.getToken() === 'keep-me', 'token 不应被清空（登录态是好的）');
    assert(replaceCalls.includes('/login'), '应跳登录页');
  });

  await testAsync('1005 校验失败：抛 ApiError 且不清 token、不跳转', async () => {
    authUtils.setAuth('t', { userId: 1, username: 'a', nickname: 'b',
      avatar: null, role: 'ADMIN' });
    replaceCalls = [];
    responses = [{ status: 200,
      data: { code: '1005', message: '单词不能为空', data: null } }];
    let err = null;
    try { await request.post('/api/admin/words', {}); } catch (e) { err = e; }
    assert(err.code === '1005', 'code 不对');
    assert(err.message === '单词不能为空', 'message 未透传: ' + err.message);
    assert(authUtils.getToken() === 't', 'token 不应被清空');
    assert(replaceCalls.length === 0, '不应跳转');
  });

  await testAsync('2004 导入格式错误：抛 ApiError', async () => {
    responses = [{ status: 200,
      data: { code: '2004', message: '导入文件格式错误', data: null } }];
    let err = null;
    try { await request.postForm('/api/admin/books/import', new FormData()); }
    catch (e) { err = e; }
    assert(err.code === '2004', 'code 不对');
  });

  await testAsync('401 但 body 不可解析：兜底当 1006 处理', async () => {
    authUtils.setAuth('t', { userId: 1, username: 'a', nickname: 'b',
      avatar: null, role: 'ADMIN' });
    replaceCalls = [];
    window.location.pathname = '/wordbank';
    responses = [{ status: 401, data: '' }];
    let err = null;
    try { await request.get('/api/books'); } catch (e) { err = e; }
    assert(err.code === '1006', '兜底应为 1006，实际 ' + err.code);
    assert(authUtils.getToken() === null, 'token 应被清空');
  });

  // ---------- 4. service 的路径与查询参数 ----------
  await testAsync('listBooks 请求 /api/admin/books 且返回裸数组', async () => {
    requests = [];
    responses = [{ data: { code: '0', message: 'ok',
      data: [{ bookId: 6, name: '四级', category: 'CET4', wordCount: 7508,
               description: null, createTime: '2026-10-16 18:39:54' }] } }];
    const books = await bookService.listBooks();
    assert(requests[0].url === '/api/admin/books', 'url 不对: ' + requests[0].url);
    assert(Array.isArray(books) && books.length === 1, '应返回数组');
    assert(books[0].wordCount === 7508, '字段读不到');
  });

  await testAsync('listWords 拼出 bookId/keyword/page/size', async () => {
    requests = [];
    responses = [{ data: { code: '0', message: 'ok',
      data: { list: [], total: 0, page: 2, size: 50 } } }];
    await wordService.listWords({ bookId: 6, keyword: 'aban', page: 2, size: 50 });
    const url = requests[0].url;
    assert(url.startsWith('/api/admin/words?'), 'url 不对: ' + url);
    assert(url.includes('bookId=6'), '缺 bookId');
    assert(url.includes('keyword=aban'), '缺 keyword');
    assert(url.includes('page=2'), '缺 page');
    assert(url.includes('size=50'), '缺 size');
  });

  await testAsync('listUsers 不发送 status 参数（后端会忽略，不做假功能）', async () => {
    requests = [];
    responses = [{ data: { code: '0', message: 'ok',
      data: { list: [], total: 0, page: 1, size: 20 } } }];
    await userService.listUsers({ page: 1, size: 20 });
    assert(!requests[0].url.includes('status'), '不应带 status: ' + requests[0].url);
  });

  await testAsync('updateUserStatus 用 PUT 且 body 为 {status:0}', async () => {
    requests = [];
    responses = [{ data: { code: '0', message: 'ok', data: { userId: 9, status: 0 } } }];
    await userService.updateUserStatus(9, 0);
    assert(requests[0].method === 'put', 'method 不对: ' + requests[0].method);
    assert(requests[0].url === '/api/admin/users/9/status', 'url 不对');
    assert(JSON.parse(requests[0].data).status === 0, 'body 不对');
  });

  await testAsync('deleteWord 用 DELETE 且路径含 wordId', async () => {
    requests = [];
    responses = [{ data: { code: '0', message: 'ok',
      data: { wordId: 77, deleted: true } } }];
    const out = await wordService.deleteWord(77);
    assert(requests[0].method === 'delete', 'method 不对');
    assert(requests[0].url === '/api/admin/words/77', 'url 不对');
    assert(out.deleted === true, '返回值不对');
  });

  await testAsync('getAiStats 请求监控接口', async () => {
    requests = [];
    responses = [{ data: { code: '0', message: 'ok',
      data: { totalCalls: 1, successRate: 100, degradedCount: 0,
              registeredUsers: 3, features: [], recentEvents: [] } } }];
    const s = await monitorService.getAiStats();
    assert(requests[0].url === '/api/admin/monitor/ai-stats', 'url 不对');
    assert(s.successRate === 100, '字段读不到');
  });

  await testAsync('importBook 用 multipart 且字段齐全', async () => {
    requests = [];
    responses = [{ data: { code: '0', message: 'ok',
      data: { bookId: 20, success: 2, skipped: 2 } } }];
    const file = new File(['{"word":"a"}'], 'test.jsonl');
    const out = await bookService.importBook(file, '测试书', 'CET4');
    assert(requests[0].url === '/api/admin/books/import', 'url 不对');
    const fd = requests[0].data;
    assert(fd instanceof FormData, '应为 FormData');
    assert(fd.get('name') === '测试书', 'name 不对');
    assert(fd.get('category') === 'CET4', 'category 不对');
    assert(fd.get('file') instanceof File, 'file 不对');
    assert(out.success === 2, '返回值不对');
    // 关键：不能手工设 Content-Type（会丢 boundary）
    const ct = requests[0].headers['Content-Type'] || requests[0].headers['content-type'];
    assert(!ct || !String(ct).includes('multipart/form-data;'), '不应手工设 boundary');
  });

  await testAsync('login 打到 /api/client/auth/login', async () => {
    requests = [];
    responses = [{ data: { code: '0', message: 'ok',
      data: { token: 'x', userInfo: { userId: 1, username: 'admin',
              nickname: 'a', avatar: null, role: 'ADMIN' } } } }];
    const out = await authService.login({ username: 'admin', password: 'p' });
    assert(requests[0].url === '/api/client/auth/login', 'url 不对');
    assert(out.userInfo.role === 'ADMIN', 'role 读不到');
  });

  // ---------- 5. 工具函数 ----------
  test('format.dash 把 null/undefined/空串显示为破折号', () => {
    assert(format.dash(null) === '—', 'null');
    assert(format.dash(undefined) === '—', 'undefined');
    assert(format.dash('') === '—', '空串');
    assert(format.dash(0) === '0', '0 不应被当成空');
  });

  test('format.categoryColor 对未知分类回落 default（不崩）', () => {
    assert(format.categoryColor('CET4') === 'blue', 'CET4');
    assert(format.categoryColor('UNKNOWN_X') === 'default', '未知分类');
  });

  test('format.featureLabel 对未知/新增功能原样返回', () => {
    assert(format.featureLabel('example') === '例句生成', 'example');
    assert(format.featureLabel('brandnew') === 'brandnew', '未知功能');
  });

  test('format.reasonColor 对未知原因回落 default', () => {
    assert(format.reasonColor('TIMEOUT') === 'orange', 'TIMEOUT');
    assert(format.reasonColor('WHATEVER') === 'default', '未知');
  });

  // ---------- 输出 ----------
  const out = results.map(([ok, name, detail]) => ({ ok, name, detail }));
  console.log('__RESULT__' + JSON.stringify(out));
})().catch((e) => {
  console.log('__FATAL__' + String(e && e.stack || e));
  process.exit(1);
});
"""


def main() -> int:
    print("=" * 72)
    print("管理端前端行为验证（执行真实源码）")
    print("=" * 72)

    io.open(ENTRY, "w", encoding="utf-8").write(ENTRY_SOURCE)

    # 前置检查：esbuild 来自 frontend/node_modules，删了依赖目录时 npx 会静默
    # 失败（stderr 为空），只打印「esbuild 失败：」看不出原因。
    if not os.path.isdir(os.path.join(FRONTEND, "node_modules")):
        print("✗ 缺少 %s/node_modules" % FRONTEND)
        print("  先执行: cd frontend && npm install")
        return 1

    # 用 esbuild 打包真实源码为 CommonJS（external 掉 axios，让 Node 用自己的）
    npx = "npx.cmd" if os.name == "nt" else "npx"
    try:
        proc = subprocess.run(
            [npx, "esbuild", ENTRY, "--bundle", "--platform=node", "--format=cjs",
             "--external:axios", "--outfile=" + BUNDLE, "--log-level=warning",
             # Vite 的 import.meta.env 在 Node/CJS 下不存在，注入一个等价对象，
             # 让被测源码保持原样（不改 request.ts 来迁就测试）
             "--define:import.meta.env={\"VITE_API_BASE_URL\":\"http://127.0.0.1:8080\"}"],
            cwd=FRONTEND, capture_output=True, text=True, timeout=180,
            encoding="utf-8", errors="replace",
        )
    except subprocess.TimeoutExpired:
        print("✗ esbuild 超时")
        return 1
    if proc.returncode != 0:
        print("✗ esbuild 失败：")
        print(proc.stderr[:2000])
        return 1
    if proc.stderr.strip():
        print("esbuild 警告：", proc.stderr[:500])

    driver_path = os.path.join(FRONTEND, ".verify_driver.cjs")
    io.open(driver_path, "w", encoding="utf-8").write(DRIVER)

    try:
        proc = subprocess.run(
            ["node", driver_path], cwd=FRONTEND,
            capture_output=True, text=True, timeout=180,
            encoding="utf-8", errors="replace",
            env={**os.environ, "NODE_NO_WARNINGS": "1"},
        )
    except subprocess.TimeoutExpired:
        print("✗ Node 执行超时")
        return 1

    stdout = proc.stdout
    if "__FATAL__" in stdout:
        print("✗ 驱动脚本崩溃：")
        print(stdout.split("__FATAL__", 1)[1][:2000])
        return 1

    marker = "__RESULT__"
    if marker not in stdout:
        print("✗ 未得到结果。stdout:")
        print(stdout[:2000])
        print("stderr:")
        print(proc.stderr[:2000])
        return 1

    payload = stdout.split(marker, 1)[1].strip().splitlines()[0]
    results = json.loads(payload)

    for item in results:
        check(item["name"], item["ok"], item.get("detail", ""))

    # 清理临时文件
    for p in (ENTRY, BUNDLE, driver_path):
        if os.path.exists(p):
            os.remove(p)

    print("\n" + "=" * 72)
    if failed:
        print("✗ %d 项通过，%d 项失败" % (passed, failed))
        print("\n失败项：")
        for f in failures:
            print("  - " + f)
        return 1
    print("✓ %d 项前端行为断言全部通过" % passed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
