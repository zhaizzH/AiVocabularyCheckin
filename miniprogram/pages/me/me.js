/**
 * 我的页（F10）。
 *
 * 用户卡（头像/昵称/@用户名/注册时间/累计打卡）+ 功能列表
 * （我的词书管理 / 更换头像 / AI 学习报告 / 关于）+ 退出登录。
 *
 * 头像一期是 emoji（constants.AVATAR_EMOJI）：不引图片资源，
 * 避免图片缺失导致编译失败；后端 avatar 字段存的是 avatar_1..6 这类 key。
 */

const authService = require('../../services/auth');
const storage = require('../../utils/storage');
const {
  AVATARS,
  AVATAR_EMOJI,
  NICKNAME_MAX_LENGTH,
  ERROR_CODES,
} = require('../../utils/constants');

Page({
  data: {
    loading: true,
    profile: null,
    avatarEmoji: '',
    registerDate: '',
    nicknameMax: NICKNAME_MAX_LENGTH,

    // 头像选择弹层
    avatarSheetVisible: false,
    avatarOptions: [],
    currentAvatar: '',

    // 昵称编辑
    editingNickname: false,
    nicknameDraft: '',
    savingNickname: false,
  },

  onLoad() {
    // 头像列表带上 emoji，WXML 里直接渲染
    const options = AVATARS.map((key) => ({
      key: key,
      emoji: AVATAR_EMOJI[key] || '🙂',
    }));
    this.setData({ avatarOptions: options });
  },

  onShow() {
    this.loadProfile();
  },

  async loadProfile() {
    this.setData({ loading: true });
    try {
      const profile = await authService.getProfile();
      this.applyProfile(profile);
      this.setData({ loading: false });
    } catch (err) {
      this.setData({ loading: false });
      wx.showToast({
        title: (err && err.message) || '加载失败',
        icon: 'none',
      });
    }
  },

  applyProfile(profile) {
    this.setData({
      profile: profile,
      avatarEmoji: AVATAR_EMOJI[profile && profile.avatar] || '🙂',
      currentAvatar: (profile && profile.avatar) || '',
      // 注册时间只展示日期部分
      registerDate: profile && profile.createTime
        ? String(profile.createTime).slice(0, 10)
        : '',
    });
  },

  // ---------------------------------------------------------- 头像

  onOpenAvatarSheet() {
    this.setData({ avatarSheetVisible: true });
  },

  onCloseAvatarSheet() {
    this.setData({ avatarSheetVisible: false });
  },

  /** 阻止弹层内部的点击冒泡到遮罩（否则点头像会直接关掉弹层）。 */
  noop() {},

  async onPickAvatar(e) {
    const key = e.currentTarget.dataset.key;
    if (!key || key === this.data.currentAvatar) {
      this.setData({ avatarSheetVisible: false });
      return;
    }

    this.setData({ avatarSheetVisible: false });
    try {
      const profile = await authService.updateProfile({ avatar: key });
      this.applyProfile(profile);
      wx.showToast({ title: '头像已更新', icon: 'none' });
    } catch (err) {
      // 失败时保持原头像（applyProfile 未被调用），只提示
      wx.showToast({
        title: (err && err.message) || '头像更新失败',
        icon: 'none',
      });
    }
  },

  // ---------------------------------------------------------- 昵称

  onEditNickname() {
    this.setData({
      editingNickname: true,
      nicknameDraft: (this.data.profile && this.data.profile.nickname) || '',
    });
  },

  onNicknameInput(e) {
    this.setData({ nicknameDraft: e.detail.value });
  },

  onCancelNickname() {
    this.setData({ editingNickname: false, nicknameDraft: '' });
  },

  /**
   * 即时保存。
   *
   * 空昵称不提交（后端会按校验失败处理，但本地先拦一道，
   * 用户能立刻看到提示而不是等一个网络往返）。
   */
  async onSaveNickname() {
    if (this.data.savingNickname) {
      return;
    }
    const draft = String(this.data.nicknameDraft || '').trim();

    if (!draft) {
      wx.showToast({ title: '昵称不能为空', icon: 'none' });
      return;
    }
    if (draft.length > NICKNAME_MAX_LENGTH) {
      wx.showToast({
        title: '昵称最多 ' + NICKNAME_MAX_LENGTH + ' 个字',
        icon: 'none',
      });
      return;
    }
    if (this.data.profile && draft === this.data.profile.nickname) {
      // 没改动就直接收起，不发请求
      this.setData({ editingNickname: false, nicknameDraft: '' });
      return;
    }

    this.setData({ savingNickname: true });
    try {
      const profile = await authService.updateProfile({ nickname: draft });
      this.applyProfile(profile);
      this.setData({ editingNickname: false, nicknameDraft: '' });
      wx.showToast({ title: '昵称已保存', icon: 'none' });
    } catch (err) {
      const code = err && err.code;
      let msg = (err && err.message) || '保存失败';
      if (code === ERROR_CODES.VALIDATION_FAILED) {
        msg = '昵称格式不符合要求';
      }
      wx.showToast({ title: msg, icon: 'none' });
    } finally {
      this.setData({ savingNickname: false });
    }
  },

  // ---------------------------------------------------------- 功能入口

  onOpenBooks() {
    wx.navigateTo({ url: '/pages/books/books' });
  },

  onOpenStats() {
    wx.navigateTo({ url: '/pages/stats/stats' });
  },

  onOpenReport() {
    wx.navigateTo({ url: '/pages/report/report' });
  },

  onOpenAbout() {
    wx.navigateTo({ url: '/pages/about/about' });
  },

  // ---------------------------------------------------------- 退出登录

  onLogout() {
    wx.showModal({
      title: '退出登录',
      content: '退出后需要重新登录才能继续学习',
      confirmText: '退出',
      confirmColor: '#d4380d',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        storage.clearAuth();
        const app = getApp();
        if (app && app.globalData) {
          app.globalData.token = '';
          app.globalData.userInfo = null;
          app.globalData.aiExampleCache = {};
        }
        wx.reLaunch({ url: '/pages/login/login' });
      },
      fail: () => {
        // modal 被系统打断时按「取消」处理，不清 token
      },
    });
  },
});
