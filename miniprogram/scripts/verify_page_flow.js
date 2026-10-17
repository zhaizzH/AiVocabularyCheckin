/**
 * 页面逻辑验证（Node 运行时 + 真实后端）。
 *
 * 为什么需要它：静态检查只能证明「没写违禁代码」，证明不了「点登录按钮真的能登录」。
 * 微信开发者工具无法在 CI/命令行里无头跑页面逻辑，所以这里用 Node 把 `wx` 运行时
 * 实现一遍（request 走真 HTTP 打到 127.0.0.1:8080），然后直接调用页面的
 * onSubmit / onWxLogin 等方法，断言它们对后端真实响应的反应是否正确。
 *
 * 覆盖的是最容易出错、又最难靠肉眼 review 的部分：
 *   - request.js 的 Result 解包 / 401 清栈 / 超时与断网分流
 *   - services 的 URL、auth 开关、mastered 传 0/1 而非布尔
 *   - 登录页成功/失败分支（token 落地、错误文案、注册后自动登录）
 *
 * 运行：node scripts/verify_page_flow.js
 * 退出码：全部通过 0，否则 1
 */

'use strict';

const http = require('http');
const path = require('path');
const assert = require('assert');

const ROOT = path.resolve(__dirname, '..');
const BASE_URL = 'http://127.0.0.1:8080';

// ---------------------------------------------------------------- 结果统计

const results = [];
let currentGroup = '';

function group(name) {
  currentGroup = name;
  console.log(`\n=== ${name} ===`);
}

async function test(name, fn) {
  try {
    await fn();
    results.push({ name, ok: true });
    console.log(`  \u2713 ${name}`);
  } catch (err) {
    results.push({ name, ok: false, err });
    console.log(`  \u2717 ${name}`);
    console.log(`      ${err && err.message ? err.message : err}`);
  }
}

// ---------------------------------------------------------------- wx 运行时模拟

/** Storage 用进程内对象模拟，和真机一样按 key 存字符串。 */
function createWxMock() {
  const storage = new Map();
  const log = { toasts: [], relaunch: [], switchTab: [], redirectTo: [], requests: [] };
  let requestOverride = null;

  function realRequest(opts) {
    const url = new URL(opts.url);
    const body = opts.data === undefined || opts.data === null
      ? null
      : JSON.stringify(opts.data);
    const headers = Object.assign({}, opts.header || {});
    if (body) {
      headers['Content-Length'] = Buffer.byteLength(body);
    }

    let settled = false;
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port || 80,
        path: url.pathname + url.search,
        method: opts.method || 'GET',
        headers,
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          if (settled) return;
          settled = true;
          const text = Buffer.concat(chunks).toString('utf8');
          let parsed = text;
          try {
            parsed = JSON.parse(text);
          } catch (e) {
            // 保持原始字符串，让调用方看到非 JSON 响应而不是被吞掉
          }
          if (opts.success) {
            opts.success({ statusCode: res.statusCode, data: parsed, header: res.headers });
          }
        });
      },
    );

    req.setTimeout(opts.timeout || 15000, () => {
      if (settled) return;
      settled = true;
      req.destroy();
      if (opts.fail) opts.fail({ errMsg: 'request:fail timeout' });
    });
    req.on('error', (e) => {
      if (settled) return;
      settled = true;
      if (opts.fail) opts.fail({ errMsg: 'request:fail ' + e.message });
    });

    if (body) req.write(body);
    req.end();
  }

  const wx = {
    getStorageSync(key) {
      return storage.has(key) ? storage.get(key) : '';
    },
    setStorageSync(key, value) {
      storage.set(key, value);
    },
    removeStorageSync(key) {
      storage.delete(key);
    },
    showToast(opts) {
      log.toasts.push(opts);
    },
    reLaunch(opts) {
      log.relaunch.push(opts);
      if (opts.complete) opts.complete();
    },
    switchTab(opts) {
      log.switchTab.push(opts);
    },
    redirectTo(opts) {
      log.redirectTo.push(opts);
    },
    login(opts) {
      opts.success({ code: 'mock_wx_code_abc' });
    },
    request(opts) {
      log.requests.push({ url: opts.url, method: opts.method || 'GET', data: opts.data });
      if (requestOverride) {
        requestOverride(opts);
        return;
      }
      realRequest(opts);
    },
  };

  return {
    wx,
    log,
    storage,
    setRequestOverride(fn) {
      requestOverride = fn;
    },
  };
}

/** 每个用例用独立模块缓存，避免 request.js 里的 redirectingToLogin 等模块态互相污染。 */
function freshModules(mock) {
  Object.keys(require.cache).forEach((k) => {
    if (k.startsWith(ROOT) && !k.includes('scripts')) {
      delete require.cache[k];
    }
  });
  global.wx = mock.wx;
  // getApp() 在真机上是单例：每次返回新对象会让页面内「读-改-写」的缓存失效。
  // mock 必须同样稳定，否则缓存用例会误报失败。
  if (!mock.app) {
    mock.app = { globalData: { token: '', userInfo: null, aiExampleCache: {} } };
  }
  global.getApp = function () {
    return mock.app;
  };
  return {
    request: require(path.join(ROOT, 'utils', 'request.js')),
    storage: require(path.join(ROOT, 'utils', 'storage.js')),
    date: require(path.join(ROOT, 'utils', 'date.js')),
    text: require(path.join(ROOT, 'utils', 'text.js')),
    auth: require(path.join(ROOT, 'services', 'auth.js')),
    book: require(path.join(ROOT, 'services', 'book.js')),
    study: require(path.join(ROOT, 'services', 'study.js')),
    review: require(path.join(ROOT, 'services', 'review.js')),
    mistake: require(path.join(ROOT, 'services', 'mistake.js')),
    stats: require(path.join(ROOT, 'services', 'stats.js')),
    ai: require(path.join(ROOT, 'services', 'ai.js')),
  };
}

/** 载入页面文件，捕获 Page(config) 并返回可调用的实例。 */
function loadPage(relPath, mock) {
  let captured = null;
  global.Page = function (config) {
    captured = config;
  };
  // 页面模块必须每次重新 require：require 有缓存，第二次直接返回上次的
  // 导出（而 Page() 只在该模块首次执行时被调用过），否则 captured 为 null。
  const abs = path.join(ROOT, relPath);
  delete require.cache[abs];
  require(abs);
  if (!captured) {
    throw new Error(`Page() 未被调用: ${relPath}`);
  }
  const inst = { data: JSON.parse(JSON.stringify(captured.data || {})) };
  Object.keys(captured).forEach((key) => {
    if (key === 'data') return;
    inst[key] = typeof captured[key] === 'function'
      ? captured[key].bind(inst)
      : captured[key];
  });
  inst.setData = function (patch) {
    Object.assign(this.data, patch);
  };
  return inst;
}

// ---------------------------------------------------------------- 用例

async function main() {
  // 后端可达性先确认，否则后面全是超时噪音
  const reachable = await new Promise((resolve) => {
    const req = http.get(BASE_URL + '/api/client/books', (res) => {
      res.resume();
      resolve(true);
    });
    req.on('error', () => resolve(false));
    req.setTimeout(5000, () => {
      req.destroy();
      resolve(false);
    });
  });
  if (!reachable) {
    console.log(`\u2717 后端不可达 ${BASE_URL}`);
    console.log('请先启动后端：cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=local');
    process.exit(1);
  }

  // ============================================================ 纯函数
  group('纯函数（utils/date.js、utils/text.js）');
  {
    const mock = createWxMock();
    const m = freshModules(mock);

    await test('buildHeatmapCells 首日非周日时补 null 到整周', () => {
      // 2026-10-16 是周五（getDay()===5），后面 7 天
      const days = [
        { date: '2026-10-16', wordCount: 0, level: 0 },
        { date: '2026-10-17', wordCount: 0, level: 0 },
        { date: '2026-10-18', wordCount: 0, level: 0 },
      ];
      const cells = m.date.buildHeatmapCells(days);
      assert.strictEqual(cells[0], null, '首列应从周日开始，周五前应有 5 个 null');
      assert.strictEqual(cells.filter((c) => c === null).length > 0, true);
      assert.strictEqual(cells.length % 7, 0, `长度应为 7 的倍数，实际 ${cells.length}`);
      const nonNull = cells.filter((c) => c !== null);
      assert.strictEqual(nonNull.length, days.length, '不得丢失或重复真实日期');
      assert.strictEqual(nonNull[0].date, '2026-10-16');
    });

    await test('buildHeatmapCells 空输入返回空数组（不崩）', () => {
      assert.deepStrictEqual(m.date.buildHeatmapCells([]), []);
      assert.deepStrictEqual(m.date.buildHeatmapCells(null), []);
    });

    await test('buildHeatmapCells 84 天输入产出 84 个非空格', () => {
      const days = [];
      const start = new Date(2026, 6, 24); // 2026-07-24 是周五
      for (let i = 0; i < 84; i++) {
        const d = new Date(start.getTime());
        d.setDate(d.getDate() + i);
        days.push({ date: m.date.toDateString(d), wordCount: i % 25, level: i % 4 });
      }
      const cells = m.date.buildHeatmapCells(days);
      assert.strictEqual(cells.filter((c) => c !== null).length, 84);
      assert.strictEqual(cells.length % 7, 0);
    });

    await test('weekdayOf 按本地时区解析（不受 UTC 偏移影响）', () => {
      // 2026-10-16 是周五 → 5
      assert.strictEqual(m.date.weekdayOf('2026-10-16'), 5);
      assert.strictEqual(m.date.weekdayOf('2026-10-18'), 0); // 周日
    });

    await test('splitTranslation 按换行与分号拆分并去空', () => {
      const r = m.text.splitTranslation('v. 放弃；抛弃\nn. 放任\n\n');
      assert.deepStrictEqual(r, ['v. 放弃', '抛弃', 'n. 放任']);
    });

    await test('splitTranslation 空输入返回空数组', () => {
      assert.deepStrictEqual(m.text.splitTranslation(''), []);
      assert.deepStrictEqual(m.text.splitTranslation(null), []);
      assert.deepStrictEqual(m.text.splitTranslation(undefined), []);
    });
  }

  // ============================================================ request.js
  group('utils/request.js 的五项职责');
  {
    const mock = createWxMock();
    const m = freshModules(mock);

    await test('成功时解包 Result 并只返回 data 字段', async () => {
      mock.setRequestOverride((opts) => {
        opts.success({
          statusCode: 200,
          data: { code: '0', message: 'ok', data: { hello: 'world' } },
        });
      });
      const data = await m.request.request({ url: '/any' });
      assert.deepStrictEqual(data, { hello: 'world' });
    });

    await test('业务错误时 reject {code, message}（不 resolve）', async () => {
      mock.setRequestOverride((opts) => {
        opts.success({
          statusCode: 200,
          data: { code: '1002', message: '用户名或密码错误', data: null },
        });
      });
      let caught = null;
      try {
        await m.request.request({ url: '/any' });
      } catch (e) {
        caught = e;
      }
      assert.ok(caught, '应当 reject');
      assert.strictEqual(caught.code, '1002');
      assert.strictEqual(caught.message, '用户名或密码错误');
    });

    await test('HTTP 401 触发清 token + reLaunch 登录页 + toast', async () => {
      m.storage.setToken('some_expired_token');
      mock.log.relaunch.length = 0;
      mock.log.toasts.length = 0;
      mock.setRequestOverride((opts) => {
        opts.success({
          statusCode: 401,
          data: { code: '1006', message: '未登录或登录已过期', data: null },
        });
      });
      await m.request.request({ url: '/any' }).catch(() => {});
      assert.strictEqual(m.storage.getToken(), '', 'token 必须被清除');
      assert.strictEqual(mock.log.relaunch.length, 1, 'reLaunch 应被调用一次');
      assert.strictEqual(mock.log.relaunch[0].url, '/pages/login/login');
      assert.strictEqual(mock.log.toasts.length, 1);
      assert.strictEqual(mock.log.toasts[0].title, '登录已过期');
    });

    await test('HTTP 200 但 code=1006 同样按登录过期处理', async () => {
      // 前一用例刚触发过 reLaunch，等防重入窗口过去，否则跳转计数为 0。
      await new Promise((r) => setTimeout(r, 1100));
      m.storage.setToken('tok');
      mock.log.relaunch.length = 0;
      mock.setRequestOverride((opts) => {
        opts.success({ statusCode: 200, data: { code: '1006', message: '过期', data: null } });
      });
      await m.request.request({ url: '/any' }).catch(() => {});
      assert.strictEqual(m.storage.getToken(), '');
      assert.strictEqual(mock.log.relaunch.length, 1);
    });

    await test('fail 中 errMsg 含 timeout → TIMEOUT 而非 NETWORK', async () => {
      mock.setRequestOverride((opts) => {
        opts.fail({ errMsg: 'request:fail timeout' });
      });
      let caught = null;
      try {
        await m.request.request({ url: '/any' });
      } catch (e) {
        caught = e;
      }
      assert.strictEqual(caught.code, 'TIMEOUT');
      assert.ok(caught.message.indexOf('超时') >= 0, `文案应含「超时」，实际: ${caught.message}`);
    });

    await test('fail 中 errMsg 无 timeout → NETWORK', async () => {
      mock.setRequestOverride((opts) => {
        opts.fail({ errMsg: 'request:fail net::ERR_INTERNET_DISCONNECTED' });
      });
      let caught = null;
      try {
        await m.request.request({ url: '/any' });
      } catch (e) {
        caught = e;
      }
      assert.strictEqual(caught.code, 'NETWORK');
    });

    await test('auth:false 时不注入 Authorization 头', async () => {
      m.storage.setToken('should_not_be_sent');
      let seenHeader = null;
      mock.setRequestOverride((opts) => {
        seenHeader = opts.header;
        opts.success({ statusCode: 200, data: { code: '0', message: 'ok', data: null } });
      });
      await m.request.request({ url: '/any', auth: false });
      assert.ok(!seenHeader.Authorization, `不应带 Authorization，实际: ${JSON.stringify(seenHeader)}`);
    });

    await test('默认注入 Bearer token', async () => {
      m.storage.setToken('abc123');
      let seenHeader = null;
      mock.setRequestOverride((opts) => {
        seenHeader = opts.header;
        opts.success({ statusCode: 200, data: { code: '0', message: 'ok', data: null } });
      });
      await m.request.request({ url: '/any' });
      assert.strictEqual(seenHeader.Authorization, 'Bearer abc123');
    });

    await test('并发 401 只跳转一次登录页（防重入）', async () => {
      // 上一用例已触发过一次跳转，防重入标志会在 1s 后复位；
      // 不等它复位就发并发请求，本用例会因为「被上一次的防重入吃掉」而假失败。
      await new Promise((r) => setTimeout(r, 1100));
      mock.log.relaunch.length = 0;
      mock.setRequestOverride((opts) => {
        opts.success({ statusCode: 401, data: { code: '1006', message: '过期', data: null } });
      });
      await Promise.all([
        m.request.request({ url: '/a' }).catch(() => {}),
        m.request.request({ url: '/b' }).catch(() => {}),
        m.request.request({ url: '/c' }).catch(() => {}),
      ]);
      assert.strictEqual(
        mock.log.relaunch.length,
        1,
        `三个并发 401 应只 reLaunch 一次，实际 ${mock.log.relaunch.length} 次`,
      );
    });
  }

  // ============================================================ services 契约
  group('services 层的 URL 与参数形态');
  {
    const mock = createWxMock();
    const m = freshModules(mock);

    function captureNext(responseData) {
      return new Promise((resolve) => {
        mock.setRequestOverride((opts) => {
          resolve(opts);
          opts.success({ statusCode: 200, data: { code: '0', message: 'ok', data: responseData } });
        });
      });
    }

    await test('auth.login 打到 /api/client/auth/login 且 auth:false', async () => {
      m.storage.setToken('stale');
      const p = captureNext({ token: 't', userInfo: {} });
      await m.auth.login({ username: 'u', password: 'p' });
      const opts = await p;
      assert.strictEqual(opts.url, BASE_URL + '/api/client/auth/login');
      assert.strictEqual(opts.method, 'POST');
      assert.ok(!opts.header.Authorization, 'auth:false 不应注入 token');
    });

    await test('auth.wxLogin 只传 {code}', async () => {
      const p = captureNext({ token: 't', userInfo: {} });
      await m.auth.wxLogin('the_code');
      const opts = await p;
      assert.strictEqual(opts.url, BASE_URL + '/api/client/auth/wx-login');
      assert.deepStrictEqual(opts.data, { code: 'the_code' });
    });

    await test('mistake.listMistakes 的 mastered 传 0/1 数字而非布尔', async () => {
      const p = captureNext({ list: [], total: 0, page: 1, size: 20 });
      await m.mistake.listMistakes({ mastered: 0, page: 2, size: 10 });
      const opts = await p;
      assert.ok(
        opts.url.indexOf('mastered=0') >= 0,
        `URL 应含 mastered=0，实际: ${opts.url}`,
      );
      assert.ok(opts.url.indexOf('page=2') >= 0);
      assert.ok(opts.url.indexOf('size=10') >= 0);
      assert.ok(opts.url.indexOf('true') < 0, '不应出现布尔字面量 true');
    });

    await test('book.listBooks 无分类时不拼 category 参数', async () => {
      const p = captureNext([]);
      await m.book.listBooks('');
      const opts = await p;
      assert.strictEqual(opts.url, BASE_URL + '/api/client/books');
    });

    await test('book.listBooks 有分类时 URL 编码', async () => {
      const p = captureNext([]);
      await m.book.listBooks('CET4');
      const opts = await p;
      assert.ok(opts.url.indexOf('category=CET4') >= 0, `实际: ${opts.url}`);
    });

    await test('ai.getExample 超时为 15000ms，report 为 60000ms', async () => {
      const p1 = captureNext({ wordId: 1 });
      await m.ai.getExample(1);
      const o1 = await p1;
      assert.strictEqual(o1.timeout, 15000, `example 超时应为 15000，实际 ${o1.timeout}`);

      const p2 = captureNext({ report: {} });
      await m.ai.getReport();
      const o2 = await p2;
      assert.strictEqual(o2.timeout, 60000, `report 超时应为 60000，实际 ${o2.timeout}`);
    });

    await test('stats.getHeatmap 默认 weeks=12', async () => {
      const p = captureNext({ days: [] });
      await m.stats.getHeatmap();
      const opts = await p;
      assert.ok(opts.url.indexOf('weeks=12') >= 0, `实际: ${opts.url}`);
    });

    await test('review.recordReview 不传 bookId（契约 §4.2）', async () => {
      const p = captureNext({ wordId: 1, stage: 2, nextReviewDate: '2026-10-20' });
      await m.review.recordReview({ wordId: 1, correct: true });
      const opts = await p;
      assert.deepStrictEqual(opts.data, { wordId: 1, correct: true });
      assert.ok(!('bookId' in opts.data), '不应带 bookId');
    });
  }

  // ============================================================ 登录页
  group('登录页（pages/login）真实链路');
  {
    const mock = createWxMock();
    const m = freshModules(mock);

    const uname = 'flow_' + Math.random().toString(36).slice(2, 10);
    const pwd = 'Test123456';

    await test('注册模式提交：注册 + 自动登录 + token 落地 + 跳首页', async () => {
      const page = loadPage('pages/login/login.js', mock);
      page.data.mode = 'register';
      page.data.username = uname;
      page.data.password = pwd;
      page.data.nickname = '流程测试';

      mock.log.switchTab.length = 0;
      mock.log.redirectTo.length = 0;

      await page.onSubmit();

      assert.ok(!page.data.errorMsg, `不应有错误，实际: ${page.data.errorMsg}`);
      assert.ok(m.storage.getToken(), 'token 应写入 storage');
      assert.strictEqual(page.data.submitting, false, 'submitting 应复位');

      const jumps = mock.log.switchTab.length + mock.log.redirectTo.length;
      assert.strictEqual(jumps, 1, '应发生一次跳转');
      if (mock.log.redirectTo.length) {
        assert.strictEqual(mock.log.redirectTo[0].url, '/pages/setup-nickname/setup-nickname');
      } else {
        assert.strictEqual(mock.log.switchTab[0].url, '/pages/index/index');
      }
    });

    await test('登录模式：错误密码 → errorMsg 有值且不跳转', async () => {
      const page = loadPage('pages/login/login.js', mock);
      page.data.mode = 'login';
      page.data.username = uname;
      page.data.password = 'definitely_wrong_password';

      mock.storage.clear();
      mock.log.switchTab.length = 0;
      mock.log.redirectTo.length = 0;

      await page.onSubmit();

      assert.strictEqual(page.data.errorMsg, '用户名或密码错误');
      assert.strictEqual(page.data.submitting, false);
      assert.strictEqual(mock.log.switchTab.length + mock.log.redirectTo.length, 0, '不应跳转');
    });

    await test('登录模式：正确密码 → token 写入且跳转', async () => {
      const page = loadPage('pages/login/login.js', mock);
      page.data.mode = 'login';
      page.data.username = uname;
      page.data.password = pwd;

      mock.storage.clear();
      mock.log.switchTab.length = 0;
      mock.log.redirectTo.length = 0;

      await page.onSubmit();

      assert.ok(!page.data.errorMsg, `不应有错误，实际: ${page.data.errorMsg}`);
      assert.ok(m.storage.getToken(), 'token 应写入');
      assert.strictEqual(mock.log.switchTab.length + mock.log.redirectTo.length, 1);
    });

    await test('前端校验：密码不足 6 位时不发请求', async () => {
      const page = loadPage('pages/login/login.js', mock);
      page.data.mode = 'login';
      page.data.username = uname;
      page.data.password = '123';

      const before = mock.log.requests.length;
      await page.onSubmit();

      assert.strictEqual(page.data.errorMsg, '密码至少 6 位');
      assert.strictEqual(mock.log.requests.length, before, '不应发起网络请求');
    });

    await test('前端校验：用户名为空时不发请求', async () => {
      const page = loadPage('pages/login/login.js', mock);
      page.data.mode = 'login';
      page.data.username = '   ';
      page.data.password = pwd;

      const before = mock.log.requests.length;
      await page.onSubmit();

      assert.strictEqual(page.data.errorMsg, '请输入用户名');
      assert.strictEqual(mock.log.requests.length, before);
    });

    await test('切换登录/注册会清空密码（避免连提错误密码）', () => {
      const page = loadPage('pages/login/login.js', mock);
      page.data.mode = 'login';
      page.data.password = 'something';
      page.switchMode({ currentTarget: { dataset: { mode: 'register' } } });
      assert.strictEqual(page.data.mode, 'register');
      assert.strictEqual(page.data.password, '');
    });

    await test('微信一键登录：wx.login 拿 code 后走 wx-login 接口', async () => {
      const page = loadPage('pages/login/login.js', mock);
      mock.storage.clear();
      const before = mock.log.requests.length;

      await page.onWxLogin();

      // 后端对 mock_wx_code_abc 必然返回 1004（无效 code），
      // 关键是「确实发出了请求」且「错误被映射成用户可读文案」。
      assert.ok(mock.log.requests.length > before, '应发起网络请求');
      const hit = mock.log.requests[mock.log.requests.length - 1];
      assert.ok(hit.url.indexOf('/api/client/auth/wx-login') >= 0, `实际: ${hit.url}`);
      assert.ok(
        page.data.errorMsg.indexOf('微信') >= 0,
        `错误文案应提到微信，实际: ${page.data.errorMsg}`,
      );
      assert.strictEqual(page.data.wxSubmitting, false, 'wxSubmitting 应复位');
    });
  }

  // ============================================================ 首页（F2）
  group('首页（pages/index）三卡并行与隔离');
  {
    const mock = createWxMock();
    const m = freshModules(mock);

    // 真实后端：先选中一本词书，让 plan.total > 0，才能验证「不写死 20」
    const uname = 'home_' + Math.random().toString(36).slice(2, 10);
    await m.auth.register({ username: uname, password: 'Test123456' });
    const login = await m.auth.login({ username: uname, password: 'Test123456' });
    m.storage.setToken(login.token);
    m.storage.setUserInfo(login.userInfo);
    const books = await m.book.listBooks('');
    await m.book.selectBook(books[0].bookId);

    await test('三卡数据成功时全部渲染，plan.total 来自后端而非写死', async () => {
      const page = loadPage('pages/index/index.js', mock);
      await page.refresh();

      assert.strictEqual(page.data.loading, false);
      assert.ok(page.data.plan, 'plan 应有值');
      assert.ok(!page.data.planError, 'plan 不应有错误: ' + page.data.planError);
      assert.ok(!page.data.reviewError, 'review 不应有错误: ' + page.data.reviewError);
      assert.ok(!page.data.streakError, 'streak 不应有错误: ' + page.data.streakError);
      assert.ok(page.data.plan.total > 0, 'total 应 > 0，实际 ' + page.data.plan.total);
      assert.ok(page.data.streak, 'streak 应有值');
    });

    await test('review 接口失败时 plan 卡仍正常显示（allSettled 隔离）', async () => {
      const page = loadPage('pages/index/index.js', mock);
      const origReq = mock.wx.request;
      mock.wx.request = function (opts) {
        if (opts.url.indexOf('/review/today') >= 0) {
          opts.fail({ errMsg: 'request:fail timeout' });
          return;
        }
        origReq.call(mock.wx, opts);
      };
      try {
        await page.refresh();
        assert.ok(page.data.reviewError, '复习卡应显示错误');
        assert.strictEqual(page.data.reviewTotal, 0);
        // 关键：另外两张卡不受影响
        assert.ok(page.data.plan, 'plan 卡不应因 review 失败而清空');
        assert.ok(!page.data.planError, 'plan 卡不应有错误: ' + page.data.planError);
        assert.ok(page.data.streak, 'streak 卡不应受影响');
      } finally {
        mock.wx.request = origReq;
      }
    });

    await test('三卡全部失败时都不崩，各卡独立显示错误', async () => {
      const page = loadPage('pages/index/index.js', mock);
      const origReq = mock.wx.request;
      mock.wx.request = function (opts) {
        opts.fail({ errMsg: 'request:fail net::ERR_INTERNET_DISCONNECTED' });
      };
      try {
        await page.refresh();
        assert.strictEqual(page.data.plan, null);
        assert.ok(page.data.planError);
        assert.ok(page.data.reviewError);
        assert.ok(page.data.streakError);
        assert.strictEqual(page.data.loading, false, 'loading 必须复位');
      } finally {
        mock.wx.request = origReq;
      }
    });

    await test('问候语按时段变化且不为空', async () => {
      const page = loadPage('pages/index/index.js', mock);
      await page.refresh();
      assert.ok(
        ['早上好', '下午好', '晚上好'].indexOf(page.data.greeting) >= 0,
        '实际: ' + page.data.greeting,
      );
    });

    await test('卡片跳转用 switchTab（tab 页不能用 navigateTo）', () => {
      const page = loadPage('pages/index/index.js', mock);
      mock.log.switchTab.length = 0;
      page.goStudy();
      page.goReview();
      page.goStats();
      assert.strictEqual(mock.log.switchTab.length, 3);
      assert.deepStrictEqual(
        mock.log.switchTab.map(function (o) { return o.url; }),
        ['/pages/study/study', '/pages/review/review', '/pages/stats/stats'],
      );
    });
  }

  // ============================================================ 词书（F3）
  group('词书页（pages/books）筛选与加入/移出');
  {
    const mock = createWxMock();
    const m = freshModules(mock);

    const uname = 'bk_' + Math.random().toString(36).slice(2, 10);
    await m.auth.register({ username: uname, password: 'Test123456' });
    const login = await m.auth.login({ username: uname, password: 'Test123456' });
    m.storage.setToken(login.token);

    await test('初始加载返回全部词书并带派生字段', async () => {
      const page = loadPage('pages/books/books.js', mock);
      await page.loadBooks();
      assert.ok(page.data.books.length > 0, '应有词书');
      const b = page.data.books[0];
      assert.ok(b.categoryLabel, 'categoryLabel 应已派生');
      assert.strictEqual(typeof b.progress, 'number', 'progress 应为数字');
      assert.ok(!Number.isNaN(b.progress), 'progress 不应是 NaN');
      assert.strictEqual(page.data.loading, false);
    });

    await test('切换分类只返回该分类词书', async () => {
      const page = loadPage('pages/books/books.js', mock);
      await page.loadBooks();
      page.onSwitchCategory({ currentTarget: { dataset: { value: 'CET4' } } });
      await new Promise(function (r) { setTimeout(r, 900); });
      assert.strictEqual(page.data.activeCategory, 'CET4');
      page.data.books.forEach(function (b) {
        assert.strictEqual(b.category, 'CET4', '混入了非 CET4: ' + b.name);
      });
    });

    await test('加入词书后 selected 变 true 且 pendingBookId 复位', async () => {
      const page = loadPage('pages/books/books.js', mock);
      page.setData({ activeCategory: '' });
      await page.loadBooks();
      const target = page.data.books.find(function (b) { return !b.selected; })
        || page.data.books[0];

      mock.wx.showModal = function (opts) { if (opts.success) opts.success({ confirm: true }); };
      await page.onToggleBook({
        currentTarget: { dataset: { bookid: target.bookId, selected: target.selected } },
      });

      await page.loadBooks();
      const after = page.data.books.find(function (b) { return b.bookId === target.bookId; });
      assert.ok(after.selected, '应已加入');
      assert.strictEqual(page.data.pendingBookId, null, 'pendingBookId 应复位');
    });

    await test('移出词书需二次确认：取消则不发请求且状态不变', async () => {
      const page = loadPage('pages/books/books.js', mock);
      page.setData({ activeCategory: '' });
      await page.loadBooks();
      const selected = page.data.books.find(function (b) { return b.selected; });
      assert.ok(selected, '前置条件：应有一本已加入的词书');

      let modalShown = false;
      mock.wx.showModal = function (opts) {
        modalShown = true;
        if (opts.success) opts.success({ confirm: false, cancel: true });
      };

      const before = mock.log.requests.length;
      await page.onToggleBook({
        currentTarget: { dataset: { bookid: selected.bookId, selected: true } },
      });

      assert.ok(modalShown, '移出必须弹二次确认');
      assert.strictEqual(mock.log.requests.length, before, '取消后不应发请求');

      await page.loadBooks();
      const still = page.data.books.find(function (b) { return b.bookId === selected.bookId; });
      assert.ok(still.selected, '取消后仍应保持已加入');
    });

    await test('移出词书确认后 selected 变 false', async () => {
      const page = loadPage('pages/books/books.js', mock);
      page.setData({ activeCategory: '' });
      await page.loadBooks();
      const selected = page.data.books.find(function (b) { return b.selected; });
      assert.ok(selected);

      mock.wx.showModal = function (opts) { if (opts.success) opts.success({ confirm: true }); };
      await page.onToggleBook({
        currentTarget: { dataset: { bookid: selected.bookId, selected: true } },
      });
      await page.loadBooks();
      const after = page.data.books.find(function (b) { return b.bookId === selected.bookId; });
      assert.strictEqual(after.selected, false);
    });

    await test('加载失败时清空列表并给出错误文案（不留旧数据）', async () => {
      const page = loadPage('pages/books/books.js', mock);
      const origReq = mock.wx.request;
      mock.wx.request = function (opts) { opts.fail({ errMsg: 'request:fail timeout' }); };
      try {
        await page.loadBooks();
        assert.deepStrictEqual(page.data.books, []);
        assert.ok(page.data.errorMsg, '应有错误文案');
        assert.strictEqual(page.data.loading, false);
      } finally {
        mock.wx.request = origReq;
      }
    });
  }

  // ============================================================ 学习（F4/F5）
  group('学习页（pages/study）逐词上报、翻面、AI 例句、打卡');
  {
    const mock = createWxMock();
    const m = freshModules(mock);

    const uname = 'st_' + Math.random().toString(36).slice(2, 10);
    await m.auth.register({ username: uname, password: 'Test123456' });
    const login = await m.auth.login({ username: uname, password: 'Test123456' });
    m.storage.setToken(login.token);
    const books = await m.book.listBooks('');
    await m.book.selectBook(books[0].bookId);

    await test('加载计划后当前词有值、义项已拆分、未翻面', async () => {
      const page = loadPage('pages/study/study.js', mock);
      await page.loadPlan();
      assert.ok(page.data.plan.total > 0, 'total 应 > 0');
      assert.ok(page.data.current, 'current 应有值');
      assert.ok(page.data.current.word, '当前词应有 word');
      assert.ok(Array.isArray(page.data.meanings) && page.data.meanings.length > 0,
        '义项应已拆分为数组');
      assert.strictEqual(page.data.flipped, false);
      assert.strictEqual(page.data.loading, false);
    });

    await test('点击卡片翻面，再点返回', async () => {
      const page = loadPage('pages/study/study.js', mock);
      await page.loadPlan();
      page.onFlip();
      assert.strictEqual(page.data.flipped, true);
      page.onFlip();
      assert.strictEqual(page.data.flipped, false);
    });

    await test('答「认识」后前进到下一个词并重置翻面/例句状态', async () => {
      const page = loadPage('pages/study/study.js', mock);
      await page.loadPlan();
      page.onFlip();
      const before = page.data.current.wordId;

      await page.onAnswer({ currentTarget: { dataset: { known: true } } });

      assert.notStrictEqual(page.data.current.wordId, before, '应前进到下一个词');
      assert.strictEqual(page.data.flipped, false, '翻面状态应重置');
      assert.strictEqual(page.data.aiExample, null, 'AI 例句应清空');
      assert.strictEqual(page.data.submitting, false, 'submitting 应复位');
    });

    await test('答「不认识」会把词收录进错题本', async () => {
      const page = loadPage('pages/study/study.js', mock);
      await page.loadPlan();
      const before = await m.mistake.listMistakes({ page: 1, size: 20 });

      await page.onAnswer({ currentTarget: { dataset: { known: false } } });

      const after = await m.mistake.listMistakes({ page: 1, size: 20 });
      assert.strictEqual(after.total, before.total + 1, '错题数应 +1');
    });

    await test('学完最后一个词后 finished 立即同步到位（不能有竞态窗口）', async () => {
      // 这个用例只调「一次」onAnswer 就断言，不靠循环重试。
      // 原因：循环会在后台 loadPlan 完成后重试，从而掩盖竞态。
      // 真实场景是用户学完最后一词立刻点打卡，若 advance() 里的 loadPlan
      // 没有被 await，此时 finished 还是旧值，打卡按钮仍禁用，用户以为白学了。
      const uname3 = 'st_' + Math.random().toString(36).slice(2, 10);
      await m.auth.register({ username: uname3, password: 'Test123456' });
      const lg3 = await m.auth.login({ username: uname3, password: 'Test123456' });
      m.storage.setToken(lg3.token);
      const bs3 = await m.book.listBooks('');
      await m.book.selectBook(bs3[0].bookId);

      const page = loadPage('pages/study/study.js', mock);
      await page.loadPlan();
      assert.strictEqual(page.data.plan.finished, 0, '新账号应从 0 开始');

      // 学到只剩最后一个词。
      // 用 index 判断进度而不是 finished：finished 靠后端返回，中途不会变
      // （只有学完最后一个词才刷新计划），拿它当循环条件会直接跑满全部词。
      const totalWords = page.data.plan.words.length;
      let guard = 0;
      while (page.data.index < totalWords - 1 && guard++ < 100) {
        await page.onAnswer({ currentTarget: { dataset: { known: true } } });
      }
      assert.strictEqual(page.data.index, totalWords - 1, '前置条件：应停在最后一个词');

      // 只调一次，await 返回后立刻断言——这才是用户点完最后一词的真实时刻
      await page.onAnswer({ currentTarget: { dataset: { known: true } } });

      assert.strictEqual(page.data.plan.finished, page.data.plan.total,
        'onAnswer 返回时 finished 必须已同步到 total，否则打卡按钮仍是禁用态');
    });

    await test('打卡成功返回连续天数与今日统计', async () => {
      const page = loadPage('pages/study/study.js', mock);
      await page.loadPlan();

      let guard = 0;
      while (guard++ < 100) {
        const plan = page.data.plan;
        if (!plan || plan.finished >= plan.total) break;
        await page.onAnswer({ currentTarget: { dataset: { known: true } } });
      }

      await page.onCheckIn();
      const r = page.data.checkInResult;
      assert.ok(r, '打卡应成功');
      assert.ok(r.streakDays >= 1, 'streakDays 应 >= 1');
      assert.ok(r.todayLearned > 0, 'todayLearned 应 > 0');
      assert.strictEqual(typeof r.todayMistakes, 'number');
      assert.strictEqual(page.data.checkInLoading, false, 'checkInLoading 应复位');
    });

    await test('重复打卡（3001）不报错弹窗，仅提示已打卡', async () => {
      const page = loadPage('pages/study/study.js', mock);
      await page.loadPlan();
      mock.log.toasts.length = 0;

      await page.onCheckIn();

      assert.ok(!page.data.checkInResult, '不应出现第二次成功页');
      const titles = mock.log.toasts.map(function (t) { return String(t.title); });
      assert.ok(
        titles.some(function (s) { return s.indexOf('已打卡') >= 0; }),
        '应提示已打卡，实际: ' + JSON.stringify(titles),
      );
      assert.strictEqual(page.data.checkInLoading, false);
    });

    await test('未学完时点打卡不发请求（前端双保险）', async () => {
      const uname2 = 'st_' + Math.random().toString(36).slice(2, 10);
      await m.auth.register({ username: uname2, password: 'Test123456' });
      const lg2 = await m.auth.login({ username: uname2, password: 'Test123456' });
      m.storage.setToken(lg2.token);
      const bs = await m.book.listBooks('');
      await m.book.selectBook(bs[0].bookId);

      const page = loadPage('pages/study/study.js', mock);
      await page.loadPlan();
      mock.log.requests.length = 0;
      mock.log.toasts.length = 0;

      await page.onCheckIn();

      assert.strictEqual(mock.log.requests.length, 0, '未完成时不应请求打卡接口');
      const titles = mock.log.toasts.map(function (t) { return String(t.title); });
      assert.ok(titles.some(function (s) { return s.indexOf('完成学习') >= 0; }),
        '应提示先完成学习，实际: ' + JSON.stringify(titles));
    });

    await test('AI 例句：degraded=true 时显示警告条且不算错误', async () => {
      const page = loadPage('pages/study/study.js', mock);
      await page.loadPlan();
      const origReq = mock.wx.request;

      mock.wx.request = function (opts) {
        if (opts.url.indexOf('/ai/example') >= 0) {
          opts.success({
            statusCode: 200,
            data: {
              code: '0', message: 'ok',
              data: {
                wordId: page.data.current.wordId,
                example: '内置例句内容',
                exampleTranslation: '内置翻译',
                mnemonic: '',
                degraded: true,
              },
            },
          });
          return;
        }
        origReq.call(mock.wx, opts);
      };
      try {
        await page.onRequestAiExample();
        assert.ok(page.data.aiExample, '降级时仍应有例句可看');
        assert.strictEqual(page.data.aiDegraded, true, '应标记降级以便显示警告条');
        assert.strictEqual(page.data.aiError, '', '降级不是错误路径');
        assert.strictEqual(page.data.aiLoading, false, 'aiLoading 应复位');
      } finally {
        mock.wx.request = origReq;
      }
    });

    await test('AI 例句：5001 超时显示超时文案且 loading 复位', async () => {
      // 必须清缓存：上一个降级用例已把同一个词的例句写进 globalData，
      // 不清会直接命中缓存，请求根本发不出去，用例假失败。
      mock.app.globalData.aiExampleCache = {};
      const page = loadPage('pages/study/study.js', mock);
      await page.loadPlan();
      const origReq = mock.wx.request;
      mock.wx.request = function (opts) {
        if (opts.url.indexOf('/ai/example') >= 0) {
          opts.success({ statusCode: 200, data: { code: '5001', message: 'AI 服务超时' } });
          return;
        }
        origReq.call(mock.wx, opts);
      };
      try {
        await page.onRequestAiExample();
        assert.strictEqual(page.data.aiExample, null);
        assert.ok(page.data.aiError.indexOf('超时') >= 0, '应提示超时: ' + page.data.aiError);
        assert.strictEqual(page.data.aiLoading, false);
      } finally {
        mock.wx.request = origReq;
      }
    });

    await test('AI 例句会话内缓存：同词第二次不再发请求', async () => {
      const page = loadPage('pages/study/study.js', mock);
      await page.loadPlan();
      await page.onRequestAiExample();
      assert.ok(mock.app.globalData.aiExampleCache, '缓存应挂在 globalData');

      const origReq = mock.wx.request;
      let netCount = 0;
      mock.wx.request = function (opts) { netCount++; return origReq.call(mock.wx, opts); };
      try {
        page.onCloseAiExample();
        await page.onRequestAiExample();
        assert.strictEqual(netCount, 0, '命中缓存不应再请求');
        assert.ok(page.data.aiExample, '缓存数据应可复用');
      } finally {
        mock.wx.request = origReq;
      }
    });
  }

  // ============================================================ 复习（F6）
  group('复习页（pages/review）待复习列表与实时移除');
  {
    const mock = createWxMock();
    const m = freshModules(mock);

    const uname = 'rv_' + Math.random().toString(36).slice(2, 10);
    await m.auth.register({ username: uname, password: 'Test123456' });
    const login = await m.auth.login({ username: uname, password: 'Test123456' });
    m.storage.setToken(login.token);

    await test('新账号今日待复习为 0，显示空态而非报错', async () => {
      const page = loadPage('pages/review/review.js', mock);
      await page.loadToday();
      assert.strictEqual(page.data.total, 0);
      assert.deepStrictEqual(page.data.words, []);
      assert.strictEqual(page.data.current, null);
      assert.strictEqual(page.data.errorMsg, '', '空列表不是错误');
      assert.strictEqual(page.data.loading, false);
    });

    await test('加载失败时给出错误文案且 loading 复位', async () => {
      const page = loadPage('pages/review/review.js', mock);
      const origReq = mock.wx.request;
      mock.wx.request = function (opts) { opts.fail({ errMsg: 'request:fail timeout' }); };
      try {
        await page.loadToday();
        assert.ok(page.data.errorMsg, '应有错误文案');
        assert.strictEqual(page.data.loading, false);
        assert.strictEqual(page.data.current, null);
      } finally {
        mock.wx.request = origReq;
      }
    });

    await test('有复习词时翻面显示释义，答对后从列表实时移除', async () => {
      // 直接注入待复习数据：新账号天然不会有今日待复习（stage=1 → 明天），
      // 而造真实数据需要改库，不适合放进前端用例。
      const page = loadPage('pages/review/review.js', mock);
      page.setData({
        loading: false,
        total: 2,
        words: [
          { wordId: 1, word: 'alpha', phonetic: '/a/', translation: 'n. 甲', stage: 2 },
          { wordId: 2, word: 'beta', phonetic: '/b/', translation: 'n. 乙', stage: 1 },
        ],
        current: { wordId: 1, word: 'alpha', phonetic: '/a/', translation: 'n. 甲', stage: 2 },
        meanings: ['n. 甲'],
        doneCount: 0,
        flipped: false,
      });

      page.onFlip();
      assert.strictEqual(page.data.flipped, true, '点卡片应翻面');
      page.onFlip();
      assert.strictEqual(page.data.flipped, false);

      const origReq = mock.wx.request;
      mock.wx.request = function (opts) {
        if (opts.url.indexOf('/review/record') >= 0) {
          opts.success({
            statusCode: 200,
            data: { code: '0', message: 'ok',
              data: { wordId: 1, stage: 3, nextReviewDate: '2026-10-20' } },
          });
          return;
        }
        origReq.call(mock.wx, opts);
      };
      try {
        await page.onAnswer({ currentTarget: { dataset: { correct: true } } });
        assert.strictEqual(page.data.words.length, 1, '已完成的词应实时移除');
        assert.strictEqual(page.data.current.wordId, 2, '应前进到下一个词');
        assert.strictEqual(page.data.doneCount, 1, '完成计数应 +1');
        assert.strictEqual(page.data.flipped, false, '翻面状态应重置');
        assert.strictEqual(page.data.submitting, false);
        assert.strictEqual(page.data.finished, false, '还有词时不应进入完成页');
      } finally {
        mock.wx.request = origReq;
      }
    });

    await test('最后一个词完成后进入「今日复习完成」页', async () => {
      const page = loadPage('pages/review/review.js', mock);
      page.setData({
        loading: false,
        total: 1,
        words: [{ wordId: 9, word: 'zeta', translation: 'n. 兹', stage: 3 }],
        current: { wordId: 9, word: 'zeta', translation: 'n. 兹', stage: 3 },
        meanings: ['n. 兹'],
        doneCount: 0,
        flipped: false,
      });

      const origReq = mock.wx.request;
      mock.wx.request = function (opts) {
        if (opts.url.indexOf('/review/record') >= 0) {
          opts.success({ statusCode: 200, data: { code: '0', message: 'ok',
            data: { wordId: 9, stage: 4, nextReviewDate: '2026-10-30' } } });
          return;
        }
        origReq.call(mock.wx, opts);
      };
      try {
        await page.onAnswer({ currentTarget: { dataset: { correct: true } } });
        assert.strictEqual(page.data.words.length, 0);
        assert.strictEqual(page.data.finished, true, '应进入完成页');
        assert.strictEqual(page.data.doneCount, 1);
        assert.strictEqual(page.data.current, null);
      } finally {
        mock.wx.request = origReq;
      }
    });

    await test('答错也移除该词并提示退回 1 天后', async () => {
      const page = loadPage('pages/review/review.js', mock);
      page.setData({
        loading: false,
        total: 2,
        words: [
          { wordId: 11, word: 'gamma', translation: 'n. 伽', stage: 3 },
          { wordId: 12, word: 'delta', translation: 'n. 德', stage: 1 },
        ],
        current: { wordId: 11, word: 'gamma', translation: 'n. 伽', stage: 3 },
        meanings: ['n. 伽'],
        doneCount: 0,
        flipped: false,
      });

      mock.log.toasts.length = 0;
      const origReq = mock.wx.request;
      mock.wx.request = function (opts) {
        if (opts.url.indexOf('/review/record') >= 0) {
          opts.success({ statusCode: 200, data: { code: '0', message: 'ok',
            data: { wordId: 11, stage: 1, nextReviewDate: '2026-10-18' } } });
          return;
        }
        origReq.call(mock.wx, opts);
      };
      try {
        await page.onAnswer({ currentTarget: { dataset: { correct: false } } });
        assert.strictEqual(page.data.words.length, 1, '答错也要从今日列表移除');
        assert.strictEqual(page.data.current.wordId, 12);
        const titles = mock.log.toasts.map(function (x) { return String(x.title); });
        assert.ok(titles.some(function (s) { return s.indexOf('退回') >= 0; }),
          '应提示退回 1 天后，实际: ' + JSON.stringify(titles));
      } finally {
        mock.wx.request = origReq;
      }
    });

    await test('1005（无学习记录）跳过该词而不是卡死', async () => {
      const page = loadPage('pages/review/review.js', mock);
      page.setData({
        loading: false,
        total: 2,
        words: [
          { wordId: 21, word: 'omega', translation: 'n. 奥', stage: 1 },
          { wordId: 22, word: 'sigma', translation: 'n. 西', stage: 1 },
        ],
        current: { wordId: 21, word: 'omega', translation: 'n. 奥', stage: 1 },
        meanings: ['n. 奥'],
        doneCount: 0,
        flipped: false,
      });

      const origReq = mock.wx.request;
      mock.wx.request = function (opts) {
        if (opts.url.indexOf('/review/record') >= 0) {
          opts.success({ statusCode: 200, data: { code: '1005', message: '该单词没有学习记录，无法复习' } });
          return;
        }
        origReq.call(mock.wx, opts);
      };
      try {
        await page.onAnswer({ currentTarget: { dataset: { correct: true } } });
        assert.strictEqual(page.data.words.length, 1, '卡死的词应被跳过');
        assert.strictEqual(page.data.current.wordId, 22);
        assert.strictEqual(page.data.submitting, false, 'submitting 应复位');
      } finally {
        mock.wx.request = origReq;
      }
    });

    await test('完成页「去学习」用 switchTab（tab 页）', () => {
      const page = loadPage('pages/review/review.js', mock);
      mock.log.switchTab.length = 0;
      page.goStudy();
      assert.strictEqual(mock.log.switchTab.length, 1);
      assert.strictEqual(mock.log.switchTab[0].url, '/pages/study/study');
    });
  }

  // ============================================================ 完整性
  group('全部 wx.request 均经 request.js 发出');
  {
    const mock = createWxMock();
    const m = freshModules(mock);
    await test('services 发出的请求都带完整 baseURL（无相对路径漏网）', async () => {
      const urls = [];
      mock.setRequestOverride((opts) => {
        urls.push(opts.url);
        opts.success({ statusCode: 200, data: { code: '0', message: 'ok', data: null } });
      });
      await m.book.listBooks('');
      await m.study.getPlan();
      await m.stats.getMastery();
      await m.ai.getReport();
      urls.forEach((u) => {
        assert.ok(u.startsWith(BASE_URL), `URL 必须以 baseURL 开头: ${u}`);
      });
      assert.strictEqual(urls.length, 4);
    });
  }

  // ---------------------------------------------------------------- 汇总
  const failed = results.filter((r) => !r.ok);
  console.log('\n' + '='.repeat(50));
  if (failed.length) {
    console.log(`${results.length - failed.length} 项通过，${failed.length} 项失败`);
    failed.forEach((f) => console.log(`  \u2717 ${f.name}`));
    process.exit(1);
  }
  console.log(`\u2713 ${results.length} 项断言全部通过`);
  process.exit(0);
}

main().catch((err) => {
  console.error('脚本自身异常:', err);
  process.exit(1);
});
