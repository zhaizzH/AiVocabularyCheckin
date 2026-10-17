/**
 * 设置昵称页（微信首登后引导）。
 *
 * 只在 userInfo.isNewUser 时进入。用户可以跳过——跳过则保留系统默认昵称，
 * 后续可在「我的」页修改，不应因为一个昵称把新用户卡在登录流程里。
 */

const authService = require('../../services/auth');
const storage = require('../../utils/storage');
const { NICKNAME_MAX_LENGTH, AVATARS, AVATAR_EMOJI } = require('../../utils/constants');

const DEFAULT_AVATAR = 'avatar_1';

// 常量映射只在模块加载时算一次：放进 onLoad 会让每次进页面都重建数组，
// 且 require 写在函数体内会让人误以为它按需加载（小程序不支持动态加载）。
const AVATAR_OPTIONS = AVATARS.map(function (value) {
  return { value: value, emoji: AVATAR_EMOJI[value] };
});

Page({
  data: {
    nickname: '',
    avatar: DEFAULT_AVATAR,
    avatars: AVATAR_OPTIONS,
    submitting: false,
    errorMsg: '',
    nicknameMaxLength: NICKNAME_MAX_LENGTH,
  },

  onInputNickname(e) {
    this.setData({ nickname: e.detail.value, errorMsg: '' });
  },

  onPickAvatar(e) {
    this.setData({ avatar: e.currentTarget.dataset.value });
  },

  async onSubmit() {
    if (this.data.submitting) {
      return;
    }

    const nickname = this.data.nickname.trim();
    if (!nickname) {
      this.setData({ errorMsg: '请输入昵称' });
      return;
    }
    if (nickname.length > NICKNAME_MAX_LENGTH) {
      this.setData({ errorMsg: '昵称最多 ' + NICKNAME_MAX_LENGTH + ' 个字符' });
      return;
    }

    this.setData({ submitting: true, errorMsg: '' });

    try {
      const profile = await authService.updateProfile({
        nickname: nickname,
        avatar: this.data.avatar,
      });
      this.persistProfile(profile);
      this.goHome();
    } catch (err) {
      this.setData({
        errorMsg: (err && err.message) || '保存失败，请稍后重试',
        submitting: false,
      });
    }
  },

  /** 跳过：不改昵称直接进首页。 */
  onSkip() {
    this.goHome();
  },

  /** 后端返回的是完整 profile，直接用最新值覆盖本地缓存，避免两处不一致。 */
  persistProfile(profile) {
    if (!profile) {
      return;
    }
    const merged = {};
    const old = storage.getUserInfo() || {};
    const keys = Object.keys(old);
    for (let i = 0; i < keys.length; i++) {
      merged[keys[i]] = old[keys[i]];
    }
    const newKeys = Object.keys(profile);
    for (let i = 0; i < newKeys.length; i++) {
      merged[newKeys[i]] = profile[newKeys[i]];
    }
    // isNewUser 是登录时的一次性标记，不应持久化到本地——
    // 否则下次启动读到 true 会又跳一次设置昵称页。
    delete merged.isNewUser;

    storage.setUserInfo(merged);
    const app = getApp();
    if (app && app.globalData) {
      app.globalData.userInfo = merged;
    }
  },

  goHome() {
    this.setData({ submitting: false });
    wx.switchTab({ url: '/pages/index/index' });
  },
});
