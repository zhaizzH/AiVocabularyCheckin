/**
 * 小程序入口。
 *
 * 只做三件事：恢复登录态到 globalData、没登录时引导去登录页、初始化会话级缓存。
 * 业务请求一律由页面通过 services → utils/request 发起，这里不发请求。
 */

const storage = require('./utils/storage');

App({
  globalData: {
    token: '',
    userInfo: null,
    /**
     * AI 例句会话缓存：{ [wordId]: responseData }。
     * 只放内存、不落 Storage——例句属于「一次会话内复用」，
     * 持久化会带来脏数据（词库更新后仍展示旧例句）。
     */
    aiExampleCache: {},
  },

  onLaunch() {
    const token = storage.getToken();
    const userInfo = storage.getUserInfo();
    if (token) {
      this.globalData.token = token;
      this.globalData.userInfo = userInfo;
      return;
    }

    // 未登录：跳登录页。
    // 用 setTimeout 0 把跳转推到下一个事件循环——onLaunch 阶段页面栈还没就绪，
    // 立即 reLaunch 在部分基础库版本上会失败（导航被丢弃），表现为白屏。
    setTimeout(function () {
      wx.reLaunch({ url: '/pages/login/login' });
    }, 0);
  },
});
