package com.vocab.dto.response;

/**
 * 注册响应（《接口详细设计》§1.1）。
 *
 * <pre>
 * { "userId": 1001, "username": "zhangsan", "nickname": "张三", "role": "USER" }
 * </pre>
 *
 * <p>契约刻意<b>不</b>在注册响应里返回 token：注册成功后由前端引导用户去登录，
 * 避免「注册即登录」隐含改变会话状态。
 */
public class RegisterResponse {

    private Long userId;
    private String username;
    private String nickname;
    private String role;

    public RegisterResponse() {
    }

    public RegisterResponse(Long userId, String username, String nickname, String role) {
        this.userId = userId;
        this.username = username;
        this.nickname = nickname;
        this.role = role;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
