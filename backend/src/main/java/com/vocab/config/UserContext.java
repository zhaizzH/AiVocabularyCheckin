package com.vocab.config;

/**
 * 当前请求的登录用户上下文。
 *
 * <p>由 {@link JwtInterceptor} 在校验通过后写入，请求结束时清理。
 * 用 {@link ThreadLocal} 承载，Service 层无需在每个方法参数里透传 userId。
 *
 * <p><b>注意</b>：必须在 {@code afterCompletion} 中调用 {@link #clear()}，
 * 否则线程池复用会导致脏数据泄漏到下一个请求。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId, String role) {
        HOLDER.set(new CurrentUser(userId, role));
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    /** 取当前用户 id；未登录返回 null（调用方需自行校验）。 */
    public static Long getUserId() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.userId();
    }

    public static String getRole() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.role();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 当前登录用户的最小信息载体。 */
    public record CurrentUser(Long userId, String role) {
    }
}
