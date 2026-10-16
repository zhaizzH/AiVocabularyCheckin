package com.vocab.common;

import java.io.Serializable;

/**
 * 统一响应包装。
 *
 * <p>契约约束（见《接口详细设计》§0）：所有接口无论成功失败都返回本结构，
 * <b>业务失败 HTTP 状态仍为 200</b>，错误通过 {@code code} 表达。
 *
 * <pre>
 * { "code": "0", "message": "ok", "data": {} }
 * </pre>
 *
 * <p>{@code code == "0"} 表示成功；失败时 {@code data} 为 {@code null}。
 */
public class Result<T> implements Serializable {

    /** 成功码，固定为字符串 "0"（前端判断 code !== "0" 即失败）。 */
    public static final String SUCCESS_CODE = "0";

    /** 成功文案。 */
    public static final String SUCCESS_MESSAGE = "ok";

    private String code;
    private String message;
    private T data;

    public Result() {
    }

    public Result(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok() {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    /** 按错误码枚举返回失败（HTTP 仍为 200，由调用方/全局处理器保证）。 */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 自定义错误码与文案。仅在错误码枚举无法覆盖时使用。 */
    public static <T> Result<T> fail(String code, String message) {
        return new Result<>(code, message, null);
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
