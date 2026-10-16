package com.vocab.config;

/**
 * 登录状态校验扩展点。
 *
 * <p>契约要求（《接口详细设计》§8.7）：管理员禁用某用户后，该用户 <b>下次请求立即失败</b>
 * （401 + 1003 文案）。这要求每个请求都校验一次 {@code user.status}，
 * 而不能只信任 token 里的内容。
 *
 * <p>本接口用于解耦：{@link JwtInterceptor} 只负责 token 解析、账号状态与角色判断，
 * 「查库校验 status」由实现方（{@code UserStatusValidatorImpl}）提供。
 * 该接口未实现时（骨架阶段）跳过校验，保证可独立启动。
 *
 * <p><b>为什么区分 DISABLED 与 UNKNOWN</b>：两者都是 401，但文案不同——
 * 「已禁用」要提示用户找管理员，「账号不存在/已注销」应引导重新登录。
 * 若都归为禁用，已逻辑删除账号的持有者会收到误导性提示。
 */
public interface UserStatusValidator {

    /** 账号当前状态。 */
    enum AccountState {

        /** 存在且 {@code status = 1}，允许继续访问。 */
        ACTIVE,

        /** 存在但 {@code status = 0}，返回 401 + 1003。 */
        DISABLED,

        /** 不存在或已逻辑删除（token 是残留凭证），返回 401 + 1006。 */
        UNKNOWN
    }

    /**
     * 查询账号当前状态。
     *
     * @param userId token 中的用户 id
     * @return 账号状态，绝不返回 {@code null}（未知一律返回 {@link AccountState#UNKNOWN}）
     */
    AccountState check(Long userId);
}
