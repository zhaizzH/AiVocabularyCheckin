/**
 * 登录态持久化。
 *
 * 只负责 token / userInfo 两个 key 的读写；页面不直接调 wx.getStorageSync，
 * 以免 key 名在十几处重复出现（改前缀时漏改一处就会丢登录态）。
 */

const { STORAGE_KEYS } = require('./constants');

function getToken() {
  try {
    return wx.getStorageSync(STORAGE_KEYS.token) || '';
  } catch (e) {
    // Storage 读写异常（配额满、数据结构损坏）不应让页面崩掉，
    // 按「未登录」处理，由页面走登录引导即可恢复。
    return '';
  }
}

function setToken(token) {
  try {
    wx.setStorageSync(STORAGE_KEYS.token, token);
  } catch (e) {
    // 写入失败只影响「下次启动是否免登录」，本次会话仍在内存里可用，故不阻断。
  }
}

function getUserInfo() {
  try {
    return wx.getStorageSync(STORAGE_KEYS.userInfo) || null;
  } catch (e) {
    return null;
  }
}

function setUserInfo(userInfo) {
  try {
    wx.setStorageSync(STORAGE_KEYS.userInfo, userInfo);
  } catch (e) {
    // 同上：仅影响下次启动的展示，不阻断本次会话。
  }
}

/** 退出登录 / 401 时统一调用，保证 token 与 userInfo 不会只清一半。 */
function clearAuth() {
  try {
    wx.removeStorageSync(STORAGE_KEYS.token);
    wx.removeStorageSync(STORAGE_KEYS.userInfo);
  } catch (e) {
    // 清理失败时至少内存态已由调用方清空（globalData），重新登录会覆盖。
  }
}

module.exports = {
  getToken,
  setToken,
  getUserInfo,
  setUserInfo,
  clearAuth,
};
