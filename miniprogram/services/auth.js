/**
 * 认证 API（契约 §1）。
 *
 * 三个公开接口必须传 auth:false：此时还没有 token，
 * 若仍注入 Authorization 头，后端仍能正常处理，但语义上是错的。
 */

const { request } = require('../utils/request');

/**
 * 注册。
 * @param {{username:string, password:string, nickname?:string}} data
 * @returns {Promise<{userId:number, username:string, nickname:string, role:string}>}
 */
function register(data) {
  return request({
    url: '/api/client/auth/register',
    method: 'POST',
    data: data,
    auth: false,
  });
}

/**
 * 账号密码登录。
 * @param {{username:string, password:string}} data
 * @returns {Promise<{token:string, userInfo:object}>}
 */
function login(data) {
  return request({
    url: '/api/client/auth/login',
    method: 'POST',
    data: data,
    auth: false,
  });
}

/**
 * 微信一键登录。
 * @param {string} code wx.login() 返回的临时凭证
 * @returns {Promise<{token:string, userInfo:object}>} userInfo.isNewUser 为 true 表示首次登录
 */
function wxLogin(code) {
  return request({
    url: '/api/client/auth/wx-login',
    method: 'POST',
    data: { code: code },
    auth: false,
  });
}

/**
 * 获取个人信息。
 * @returns {Promise<{userId:number, username:string, nickname:string, avatar:string,
 *   role:string, createTime:string, totalCheckInDays:number}>}
 */
function getProfile() {
  return request({ url: '/api/client/auth/profile' });
}

/**
 * 修改个人信息（昵称/头像，至少一项）。
 * @param {{nickname?:string, avatar?:string}} data
 * @returns {Promise<object>} 更新后的完整 profile
 */
function updateProfile(data) {
  return request({
    url: '/api/client/auth/profile',
    method: 'PUT',
    data: data,
  });
}

module.exports = { register, login, wxLogin, getProfile, updateProfile };
