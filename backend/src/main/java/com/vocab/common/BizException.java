package com.vocab.common;

/**
 * 业务异常。
 *
 * <p>所有可预期的业务失败（"未选择词库"、"今日已打卡"）由 Service 抛本异常，
 * 由 {@link GlobalExceptionHandler} 统一转换为 {@link Result} 包装结构。
 * <b>Controller 绝不手工拼装错误响应体。</b>
 *
 * <p>本规模下刻意不搞按模块划分的异常继承体系——一个「错误码 + 消息」足够。
 */
public class BizException extends RuntimeException {

    private final String code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 自定义错误码与文案。仅在 {@link ErrorCode} 无法覆盖时使用
     * （例如需要拼接上下文的场景），否则优先用 {@link #BizException(ErrorCode)}。
     */
    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
