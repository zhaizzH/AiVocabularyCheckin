/**
 * 全局唯一网络出口。
 *
 * 所有请求必须经过本函数，页面与服务层禁止直接 wx.request。这里集中处理四件事：
 *   1. 拼接 baseURL
 *   2. 注入 Authorization（公开接口可传 auth:false 跳过）
 *   3. 统一解包 Result：code !== '0' 一律 reject，页面只需 try/catch
 *   4. 401 / 1006 清 token 并回登录页（reLaunch 清栈，避免返回已失效页面）
 *
 * 本层**不弹业务错误 toast**：同一个错误在不同页面的呈现不同
 * （有的要行内提示、有的要进降级态），由页面决定。
 */

const { BASE_URL, CLIENT_ERROR_CODES } = require('./constants');
const storage = require('./storage');

let redirectingToLogin = false;

/**
 * 登录过期统一处理。
 *
 * 两件事的「幂等性」不同，分开写更稳健：
 *   - 清 token：**先做**。它本身幂等，且早清一步不会错。
 *   - 弹提示 + 跳转：**只做一次**。同批 401 若跳三次，会连建三次登录页并弹三个 toast。
 *
 * 实测说明：即使把清 token 放到防重入之后，同批 401 场景下结果也一样
 * （第一个请求已经把 token 清空了）。所以这里选「先清」是为了稳健，
 * 不是修了个能复现的缺陷——注释不夸大它。
 */
function handleUnauthorized() {
  storage.clearAuth();
  const app = getApp();
  if (app && app.globalData) {
    app.globalData.token = '';
    app.globalData.userInfo = null;
  }

  if (redirectingToLogin) {
    return;
  }
  redirectingToLogin = true;

  wx.showToast({ title: '登录已过期', icon: 'none' });
  wx.reLaunch({
    url: '/pages/login/login',
    complete() {
      // 延后复位：既避免同批 401 重复跳转，又不会永久卡死——
      // 用户重新登录后若再次过期，本函数必须还能生效。
      setTimeout(function () {
        redirectingToLogin = false;
      }, 1000);
    },
  });
}

/**
 * 发起请求。
 *
 * @param {object} options
 * @param {string} options.url       以 / 开头的路径，如 '/api/client/study/plan'
 * @param {string} [options.method]  'GET' | 'POST' | 'PUT' | 'DELETE'，默认 GET
 * @param {object} [options.data]    请求体（GET 时由 wx.request 自动拼成 query）
 * @param {number} [options.timeout] 毫秒，默认 15000
 * @param {boolean} [options.auth]   是否注入 token，默认 true
 * @returns {Promise<*>} resolve 后端 data 字段；reject {code: string, message: string}
 */
function request(options) {
  const url = options.url;
  const method = options.method || 'GET';
  const data = options.data;
  const timeout = options.timeout || 15000;
  const needAuth = options.auth !== false;

  return new Promise(function (resolve, reject) {
    const header = { 'Content-Type': 'application/json' };
    if (needAuth) {
      const token = storage.getToken();
      if (token) {
        header.Authorization = 'Bearer ' + token;
      }
    }

    wx.request({
      url: BASE_URL + url,
      method: method,
      data: data,
      header: header,
      timeout: timeout,
      success(res) {
        const statusCode = res.statusCode;
        const body = res.data || {};

        // HTTP 401：token 缺失/过期/账号被禁用。响应体虽也是 Result 结构，
        // 但此时应优先按「重新登录」处理，而不是把 1006 文案原样弹给用户。
        if (statusCode === 401) {
          handleUnauthorized();
          reject({
            code: String(body.code || '1006'),
            message: body.message || '登录已过期',
          });
          return;
        }

        // 业务错误：HTTP 200 但 code 非 '0'（契约 §0）。
        if (body.code !== '0') {
          const code = String(body.code || '');
          if (code === '1006') {
            handleUnauthorized();
          }
          reject({ code: code, message: body.message || '请求失败' });
          return;
        }

        resolve(body.data);
      },
      fail(err) {
        const errMsg = (err && err.errMsg) || '';
        // wx.request 的超时与断网都走 fail，只能靠 errMsg 区分：
        // 若统一报「网络异常」，用户会以为是信号问题而反复重试，掩盖真正的超时。
        if (errMsg.indexOf('timeout') >= 0) {
          reject({
            code: CLIENT_ERROR_CODES.TIMEOUT,
            message: '请求超时，请稍后重试',
          });
          return;
        }
        reject({
          code: CLIENT_ERROR_CODES.NETWORK,
          message: '网络异常，请检查网络后重试',
        });
      },
    });
  });
}

module.exports = { request };
