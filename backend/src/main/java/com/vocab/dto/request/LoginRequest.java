package com.vocab.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 账号密码登录请求（《接口详细设计》§1.2）。
 *
 * <p>这里刻意<b>不做</b>格式校验（如密码长度/复杂度）：登录只需校验
 * 「账号密码是否匹配」，格式类提示属于注册。若在登录时套用注册的复杂度规则，
 * 历史用户（规则变更前注册）会被直接挡在门外。失败统一由 Service 抛 1002。
 */
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
