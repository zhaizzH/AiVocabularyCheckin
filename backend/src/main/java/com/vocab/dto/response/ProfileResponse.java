package com.vocab.dto.response;

import java.time.LocalDateTime;

/**
 * 个人信息响应（《接口详细设计》§1.4 / §1.5）。
 *
 * <pre>
 * { "userId": 1001, "username": "zhangsan", "nickname": "张三", "avatar": "avatar_1",
 *   "role": "USER", "createTime": "2026-10-18 09:30:00", "totalCheckInDays": 12 }
 * </pre>
 *
 * <p><b>注意</b>：profile 响应是<b>扁平</b>结构，不像 login 那样嵌在 {@code userInfo} 下。
 * 这是契约原文的差异（§1.2 有 {@code userInfo} 包装，§1.4 没有），不要「顺手统一」。
 *
 * <p>{@code createTime} 是 {@link LocalDateTime}，格式由 {@code JacksonConfig}
 * 统一为 {@code yyyy-MM-dd HH:mm:ss}（空格分隔，非 ISO 的 {@code T}）。
 */
public class ProfileResponse {

    private Long userId;
    private String username;

    /** 微信登录用户的 username 为 null，前端需兜底展示昵称。 */
    private String nickname;
    private String avatar;
    private String role;
    private LocalDateTime createTime;

    /**
     * 累计打卡天数：不同 {@code check_in_date} 的天数
     * （契约 §1.4；SQL 见 {@code StatsMapper.countCheckInDays}）。
     */
    private Integer totalCheckInDays;

    public ProfileResponse() {
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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public Integer getTotalCheckInDays() {
        return totalCheckInDays;
    }

    public void setTotalCheckInDays(Integer totalCheckInDays) {
        this.totalCheckInDays = totalCheckInDays;
    }
}
