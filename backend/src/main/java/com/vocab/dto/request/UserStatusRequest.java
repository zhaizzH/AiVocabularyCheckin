package com.vocab.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * 禁用 / 启用用户请求（《接口详细设计》§8.7）。
 *
 * <pre>
 * { "status": 0 }   // 0 禁用 / 1 启用
 * </pre>
 *
 * <p><b>为什么用 {@code Integer} 而不是 {@code int}</b>：{@code int} 的默认值 0
 * 恰好是「禁用」这个有破坏力的取值——前端漏传字段会被静默解读为「禁用该用户」。
 * 用 {@link Integer} + {@link NotNull} 把「漏传」变成 1005 参数错误。
 *
 * <p><b>为什么用 {@code @NotNull} 而不加 {@code @Min/@Max} 自定义消息</b>：
 * 取值只允许 0/1，超出范围同属参数错误，统一由
 * {@code AdminServiceImpl} 校验后抛 1005——那里能同时校验
 * 「字段是否合法」与「目标用户是否 ADMIN」（后者需要查库，注解做不到）。
 */
public class UserStatusRequest {

    @NotNull(message = "status 不能为空")
    private Integer status;

    public UserStatusRequest() {
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
