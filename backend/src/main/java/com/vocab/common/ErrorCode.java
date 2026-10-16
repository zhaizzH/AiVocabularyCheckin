package com.vocab.common;

/**
 * 错误码总表。
 *
 * <p>与《接口详细设计》§0 的错误码总表严格对应，按模块分段：
 * <ul>
 *   <li>1xxx 认证/用户</li>
 *   <li>2xxx 词库</li>
 *   <li>3xxx 学习/打卡</li>
 *   <li>4xxx 错题</li>
 *   <li>5xxx AI 服务</li>
 *   <li>9xxx 系统</li>
 * </ul>
 *
 * <p>{@code message} 是可直接展示给用户的中文文案。
 */
public enum ErrorCode {

    // ---------------- 1xxx 认证 / 用户 ----------------
    USERNAME_TAKEN("1001", "用户名已被占用"),
    LOGIN_FAILED("1002", "用户名或密码错误"),
    USER_DISABLED("1003", "账号已被禁用，请联系管理员"),
    WX_LOGIN_FAILED("1004", "微信登录失败"),
    PARAM_INVALID("1005", "参数校验失败"),
    /**
     * token 缺失 / 格式错误 / 过期 / 签名无效。
     *
     * <p>说明：契约 §0 将「401」统一描述为「token 缺失/过期/账号禁用」，
     * 但只为「账号禁用」分配了 1003，未为「token 无效/过期」分配码值。
     * 此处补 1006 以避免与「账号禁用」语义混淆，属契约增补项，
     * 见 research/01-contract-gaps-discovered.md。
     */
    TOKEN_INVALID("1006", "登录状态已失效，请重新登录"),
    /**
     * 非管理员访问 {@code /api/admin/**}（HTTP 403）。
     *
     * <p>契约 §0 只规定「403 = 非管理员访问管理端」，未分配码值。
     * 此处补 1007，使 403 响应体也符合 {@code Result} 结构。
     */
    FORBIDDEN("1007", "无权访问该资源"),

    // ---------------- 2xxx 词库 ----------------
    BOOK_NOT_FOUND("2001", "词书不存在"),
    BOOK_NOT_SELECTED("2002", "未选择该词书"),
    BOOK_ALREADY_SELECTED("2003", "词书已加入"),
    IMPORT_FILE_INVALID("2004", "导入文件格式错误"),

    // ---------------- 3xxx 学习 / 打卡 ----------------
    ALREADY_CHECKED_IN("3001", "今日已打卡"),
    NO_STUDY_PLAN("3002", "今日无学习计划"),
    PLAN_NOT_FINISHED("3003", "学习计划未完成不可打卡"),

    // ---------------- 4xxx 错题 ----------------
    MISTAKE_NOT_FOUND("4001", "错题不存在"),
    MISTAKE_ALREADY_MASTERED("4002", "错题已掌握"),

    // ---------------- 5xxx AI 服务 ----------------
    AI_TIMEOUT("5001", "AI 服务超时"),
    AI_ERROR("5002", "AI 服务异常"),
    AI_DATA_INSUFFICIENT("5003", "学习数据不足无法生成报告"),

    // ---------------- 9xxx 系统 ----------------
    SYSTEM_BUSY("9001", "系统繁忙"),

    /**
     * 以下为 HTTP 协议层错误码。
     *
     * <p>契约 §0 仅约定「业务失败 HTTP 200」，未覆盖路由/方法/媒体类型错误。
     * 此类错误必须保留 4xx 语义，否则联调时无法区分「路径写错」与「服务故障」。
     * 为让响应体仍符合 {@code Result} 结构，补以下码值。
     */
    NOT_FOUND("9002", "请求的接口不存在"),
    METHOD_NOT_ALLOWED("9003", "请求方法不支持"),
    UNSUPPORTED_MEDIA_TYPE("9004", "不支持的 Content-Type");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /** 按 code 反查枚举（找不到返回 null）。 */
    public static ErrorCode fromCode(String code) {
        for (ErrorCode ec : values()) {
            if (ec.code.equals(code)) {
                return ec;
            }
        }
        return null;
    }
}
