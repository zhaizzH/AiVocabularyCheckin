package com.vocab.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 登录响应中的 {@code userInfo}（《接口详细设计》§1.2 / §1.3）。
 *
 * <pre>
 * { "userId": 1001, "username": "zhangsan", "nickname": "张三",
 *   "avatar": "avatar_1", "role": "USER" }
 * </pre>
 *
 * <p>{@code isNewUser} 只在<b>微信登录首次自动建号</b>时出现——
 * 契约 §1.3 的描述是「响应 data：同 1.2，额外多一个字段」，
 * 即该字段是微信登录路径相对账号密码登录的增量。
 * 字段级 {@code @JsonInclude(NON_NULL)} 覆盖
 * {@code application.yml} 的全局 {@code default-property-inclusion: always}，
 * 使账号密码登录的响应体与 §1.2 示例逐字一致。
 */
public class UserInfoResponse {

    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private String role;

    /** true = 微信首次登录自动建号，前端应引导进入「设置昵称」页。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean isNewUser;

    public UserInfoResponse() {
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

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Boolean getIsNewUser() {
        return isNewUser;
    }

    public void setIsNewUser(Boolean isNewUser) {
        this.isNewUser = isNewUser;
    }
}
