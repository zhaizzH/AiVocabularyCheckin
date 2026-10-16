package com.vocab.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户表 {@code user} 实体（S3 §7.2 表 7.1）。
 *
 * <p>账号密码用户：{@code username} + {@code passwordHash} 有值、{@code openid} 为 null；
 * 微信登录用户：{@code openid} 有值、{@code username}/{@code passwordHash} 为 null
 * （MySQL 唯一索引允许多个 NULL，语义正确）。
 *
 * <p><b>绝不直接对外返回本实体</b>（会泄露 {@code passwordHash} / {@code deleted}），
 * 必须在 Service 层映射为 DTO。
 */
@TableName("user")
public class User {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户名，唯一索引 uk_username；微信登录用户可为 NULL。 */
    private String username;

    /** BCrypt 密码哈希；微信登录用户为 NULL。绝不记录到日志。 */
    private String passwordHash;

    /** 微信 openid，唯一索引 uk_openid；账号密码用户为 NULL。 */
    private String openid;

    private String nickname;

    /** 头像（一期为内置值 avatar_1 ~ avatar_6）。 */
    private String avatar;

    /** USER / ADMIN。 */
    private String role;

    /** 1 正常 / 0 禁用。禁用即时生效由拦截器每请求校验。 */
    private Integer status;

    /** 逻辑删除标志：0 未删除 / 1 已删除。 */
    @TableLogic
    private Integer deleted;

    /** 创建时间。数据库无默认值，由 MetaObjectHandler 填充。 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间。数据库无默认值，由 MetaObjectHandler 填充。 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getOpenid() {
        return openid;
    }

    public void setOpenid(String openid) {
        this.openid = openid;
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
