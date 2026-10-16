package com.vocab.dto.response;

/**
 * 登录响应（《接口详细设计》§1.2 账号密码登录 / §1.3 微信登录）。
 *
 * <pre>
 * { "token": "eyJhbGciOi...", "userInfo": { ... } }
 * </pre>
 *
 * <p>两个登录接口共用本结构，差异只体现在 {@link UserInfoResponse#getIsNewUser()}
 * 是否出现（仅微信首次建号时为 true）。
 *
 * <p><b>绝不记录 token 到日志</b>（logging-guidelines.md 敏感信息红线）。
 */
public class LoginResponse {

    private String token;
    private UserInfoResponse userInfo;

    public LoginResponse() {
    }

    public LoginResponse(String token, UserInfoResponse userInfo) {
        this.token = token;
        this.userInfo = userInfo;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public UserInfoResponse getUserInfo() {
        return userInfo;
    }

    public void setUserInfo(UserInfoResponse userInfo) {
        this.userInfo = userInfo;
    }
}
