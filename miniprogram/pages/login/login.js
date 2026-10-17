/**
 * 登录页（F1）。
 *
 * 两条入口：
 *   1. 账号密码登录/注册（教室内网可用，且是 F2–F10 验收的主要路径）
 *   2. 微信一键登录（调用 wx.login 拿 code 换 openid）
 *
 * 已登录用户进入本页应直接放行到首页——否则点 tabBar 回来会看到一个
 * 已经登录却能再登一次的页面。
 */

const authService = require('../../services/auth');
const storage = require('../../utils/storage');
const { ERROR_CODES, NICKNAME_MAX_LENGTH } = require('../../utils/constants');

Page({
  data: {
    // 'login' | 'register'
    mode: 'login',
    username: '',
    password: '',
    nickname: '',
    submitting: false,
    wxSubmitting: false,
    errorMsg: '',
    nicknameMaxLength: NICKNAME_MAX_LENGTH,
  },

  onShow() {
    // 已有 token 说明已登录，直接回首页（用 switchTab，首页是 tab 页）
    if (storage.getToken()) {
      wx.switchTab({ url: '/pages/index/index' });
    }
  },

  onInputUsername(e) {
    this.setData({ username: e.detail.value, errorMsg: '' });
  },

  onInputPassword(e) {
    this.setData({ password: e.detail.value, errorMsg: '' });
  },

  onInputNickname(e) {
    this.setData({ nickname: e.detail.value, errorMsg: '' });
  },

  switchMode(e) {
    const mode = e.currentTarget.dataset.mode;
    if (mode === this.data.mode) {
      return;
    }
    // 切换时不保留密码：用户发现输错后切到注册再切回来，多半是重输，
    // 保留反而容易把错误密码连着提交两次。
    this.setData({ mode: mode, password: '', errorMsg: '' });
  },

  /** 表单前置校验：只挡明显不合法的输入，其余交给后端（后端有同一套规则）。 */
  validate() {
    const username = this.data.username.trim();
    const password = this.data.password;

    if (!username) {
      this.setData({ errorMsg: '请输入用户名' });
      return false;
    }
    if (username.length < 3) {
      this.setData({ errorMsg: '用户名至少 3 个字符' });
      return false;
    }
    if (!password) {
      this.setData({ errorMsg: '请输入密码' });
      return false;
    }
    if (password.length < 6) {
      this.setData({ errorMsg: '密码至少 6 位' });
      return false;
    }
    return true;
  },

  /** 账号密码提交（登录或注册）。 */
  async onSubmit() {
    if (this.data.submitting || this.data.wxSubmitting) {
      return;
    }
    if (!this.validate()) {
      return;
    }

    const isRegister = this.data.mode === 'register';
    this.setData({ submitting: true, errorMsg: '' });

    try {
      if (isRegister) {
        await authService.register({
          username: this.data.username.trim(),
          password: this.data.password,
          nickname: this.data.nickname.trim() || undefined,
        });
      }

      // 注册成功后直接走登录，避免用户再输一遍密码。
      const result = await authService.login({
        username: this.data.username.trim(),
        password: this.data.password,
      });
      this.handleLoginSuccess(result);
    } catch (err) {
      this.setData({
        errorMsg: this.mapError(err, isRegister),
        submitting: false,
      });
    }
  },

  /** 微信一键登录。 */
  async onWxLogin() {
    if (this.data.submitting || this.data.wxSubmitting) {
      return;
    }
    this.setData({ wxSubmitting: true, errorMsg: '' });

    try {
      const code = await this.getWxCode();
      const result = await authService.wxLogin(code);
      this.handleLoginSuccess(result);
    } catch (err) {
      this.setData({
        errorMsg: this.mapError(err, false),
        wxSubmitting: false,
      });
    }
  },

  /** 把 wx.login 的回调式 API 包成 Promise。 */
  getWxCode() {
    return new Promise(function (resolve, reject) {
      wx.login({
        success(res) {
          if (res.code) {
            resolve(res.code);
            return;
          }
          // code 缺失通常是开发者工具未登录或 appid 不匹配，
          // 报「授权失败」比把空 code 发给后端更容易定位。
          reject({ code: ERROR_CODES.WX_LOGIN_FAILED, message: '微信授权失败，请重试' });
        },
        fail() {
          reject({ code: ERROR_CODES.WX_LOGIN_FAILED, message: '微信授权失败，请重试' });
        },
      });
    });
  },

  /**
   * 登录成功后的统一收尾。
   * 微信首登（isNewUser）需要去设置昵称页，否则昵称是系统默认的一串字符。
   */
  handleLoginSuccess(result) {
    const token = result.token;
    const userInfo = result.userInfo || {};

    storage.setToken(token);
    storage.setUserInfo(userInfo);
    const app = getApp();
    if (app && app.globalData) {
      app.globalData.token = token;
      app.globalData.userInfo = userInfo;
    }

    this.setData({ submitting: false, wxSubmitting: false });

    if (userInfo.isNewUser) {
      // 用 redirectTo 而非 navigateTo：登录页不应留在栈里，否则用户
      // 在昵称页按返回会回到已经登录成功的登录页。
      wx.redirectTo({ url: '/pages/setup-nickname/setup-nickname' });
      return;
    }

    wx.switchTab({ url: '/pages/index/index' });
  },

  /**
   * 错误码 → 用户文案。
   *
   * 只对「用户能自己修正」的错误给出具体指引；系统类错误统一给一句
   * 「稍后重试」，避免把后端内部细节（甚至堆栈）暴露给用户。
   */
  mapError(err, isRegister) {
    const code = err && err.code;

    if (code === ERROR_CODES.USERNAME_TAKEN) {
      return '该用户名已被注册，请换一个';
    }
    if (code === ERROR_CODES.BAD_CREDENTIALS) {
      return '用户名或密码错误';
    }
    if (code === ERROR_CODES.USER_DISABLED) {
      return '账号已被禁用，请联系管理员';
    }
    if (code === ERROR_CODES.VALIDATION_FAILED) {
      // 后端会给出具体是哪个字段不合法，直接透出更有用
      return (err && err.message) || '输入内容不符合要求';
    }
    if (code === ERROR_CODES.WX_LOGIN_FAILED) {
      return '微信登录失败，请改用账号密码登录';
    }
    if (code === 'TIMEOUT') {
      return '请求超时，请检查网络后重试';
    }
    if (code === 'NETWORK') {
      return '网络异常，请检查网络后重试';
    }
    if (code === ERROR_CODES.SYSTEM_BUSY) {
      return '服务繁忙，请稍后重试';
    }
    if (isRegister) {
      return '注册失败，请稍后重试';
    }
    return '登录失败，请稍后重试';
  },
});
