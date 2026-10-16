package com.vocab.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 微信登录请求（《接口详细设计》§1.3）。
 *
 * <p>{@code code} 是小程序 {@code wx.login()} 返回的临时凭证，
 * 由后端通过 {@code code2Session} 换取 openid。它是**一次性**的
 * （几分钟内过期且只能换一次），因此绝不缓存或记入日志。
 */
public class WxLoginRequest {

    @NotBlank(message = "微信登录凭证不能为空")
    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
