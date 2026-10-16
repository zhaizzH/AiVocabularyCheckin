package com.vocab.config;

/**
 * 登录状态校验扩展点。
 *
 * <p>契约要求（《接口详细设计》§8.7）：管理员禁用某用户后，该用户 <b>下次请求立即失败</b>
 * （401 + 1003 文案）。这要求每个请求都校验一次 {@code user.status}，
 * 而不能只信任 token 里的内容。
 *
 * <p>本接口用于解耦：{@link JwtInterceptor} 只负责 token 解析与角色判断，
 * 「查库校验 status」由实现方（阶段 5 的 {@code AuthService}）提供。
 * 在真实实现就位之前，容器中会存在一个放行的默认实现，
 * 保证骨架阶段可独立启动与自测。
 */
public interface UserStatusValidator {

    /**
     * 校验用户当前是否可继续访问。
     *
     * @param userId token 中的用户 id
     * @return true = 允许访问；false = 账号已被禁用
     */
    boolean isActive(Long userId);
}
