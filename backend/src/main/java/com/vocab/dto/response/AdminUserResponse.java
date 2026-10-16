package com.vocab.dto.response;

import java.time.LocalDateTime;

/**
 * 管理端用户列表元素（《接口详细设计》§8.6）。
 *
 * <pre>
 * { "userId": 1001, "username": "zhangsan", "nickname": "张三", "role": "USER",
 *   "status": 1, "totalCheckInDays": 12, "createTime": "2026-10-18 09:30:00" }
 * </pre>
 *
 * <p><b>绝不直接返回 {@code User} 实体</b>（quality-guidelines.md 禁用模式）：
 * 实体带 {@code passwordHash} 与 {@code deleted}，序列化出去就是密码哈希泄露。
 * 本 DTO 只包含管理端界面真正需要的字段（admin-prototype.html 用户表格列）。
 *
 * <p>{@code username} 对微信登录用户为 {@code null}（无账号密码），
 * 前端需以 {@code nickname} 兜底展示；此处不做「填占位符」的加工——
 * 契约给的是原始值，展示格式属前端职责。
 */
public class AdminUserResponse {

    private Long userId;
    private String username;
    private String nickname;

    /** USER / ADMIN（前端据此决定是否禁用「禁用」按钮）。 */
    private String role;

    /** 1 正常 / 0 禁用。 */
    private Integer status;

    /** 累计打卡天数（不同 {@code check_in_date} 的天数，同 §1.4 口径）。 */
    private Integer totalCheckInDays;

    /** 注册时间，由 JacksonConfig 输出为 {@code yyyy-MM-dd HH:mm:ss}。 */
    private LocalDateTime createTime;

    public AdminUserResponse() {
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getTotalCheckInDays() {
        return totalCheckInDays;
    }

    public void setTotalCheckInDays(Integer totalCheckInDays) {
        this.totalCheckInDays = totalCheckInDays;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
