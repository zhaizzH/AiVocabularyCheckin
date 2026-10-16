package com.vocab.service;

import com.vocab.dto.request.LoginRequest;
import com.vocab.dto.request.RegisterRequest;
import com.vocab.dto.request.UpdateProfileRequest;
import com.vocab.dto.request.WxLoginRequest;
import com.vocab.dto.response.LoginResponse;
import com.vocab.dto.response.ProfileResponse;
import com.vocab.dto.response.RegisterResponse;

/**
 * 认证与用户信息服务。
 *
 * <p>契约见《接口详细设计》§1（认证模块）；行为规则见《需求清单》§1。
 *
 * <p>Service 层承载全部业务规则并抛出 {@code BizException}；
 * Controller 只做参数校验与结果包装（quality-guidelines.md）。
 */
public interface AuthService {

    /** 注册（§1.1）。重复用户名 → 1001。 */
    RegisterResponse register(RegisterRequest request);

    /** 账号密码登录（§1.2）。凭证错误 → 1002；账号禁用 → 1003。 */
    LoginResponse login(LoginRequest request);

    /** 微信登录（§1.3）。code 无效或微信侧异常 → 1004。 */
    LoginResponse wxLogin(WxLoginRequest request);

    /** 查询个人信息（§1.4），含累计打卡天数。 */
    ProfileResponse profile(Long userId);

    /** 修改个人信息（§1.5）。参数违规 → 1005。 */
    ProfileResponse updateProfile(Long userId, UpdateProfileRequest request);
}
