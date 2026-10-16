package com.vocab.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>契约约束（见《接口详细设计》§0 与 spec/error-handling.md）：
 * <ul>
 *   <li>业务失败（{@link BizException}）→ <b>HTTP 200</b> + 对应错误码</li>
 *   <li>参数校验失败 → HTTP 200 + 1005 + 第一个字段的提示信息</li>
 *   <li>未预期异常 → HTTP 200 + 9001，并在日志中记录一次完整堆栈</li>
 * </ul>
 *
 * <p>注意：本项目中 HTTP 4xx/5xx 仅保留给「认证拦截器拒绝」与「体系层面崩溃」，
 * 正常业务错误一律走 200 + code。这是前端契约（小程序按 {@code code !== "0"} 判断）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 可预期的业务失败。不记堆栈——业务失败是正常流程，不是故障。 */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        log.debug("业务异常 code={} message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** @Valid 校验请求体失败：取第一个字段错误的提示信息。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse(ErrorCode.PARAM_INVALID.getMessage());
        log.debug("请求体校验失败: {}", collectFieldErrors(e));
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), message);
    }

    /** 表单/查询参数绑定失败。 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse(ErrorCode.PARAM_INVALID.getMessage());
        log.debug("参数绑定失败: {}", collectFieldErrors(e));
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), message);
    }

    /** @Validated 在方法参数（如 @RequestParam）上的约束失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse(ErrorCode.PARAM_INVALID.getMessage());
        log.debug("参数约束失败: {}", message);
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), message);
    }

    /** 缺少必填请求参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.debug("缺少请求参数: {}", e.getParameterName());
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "缺少必填参数：" + e.getParameterName());
    }

    /** 参数类型不匹配（如 page=abc）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.debug("参数类型不匹配: name={} value={}", e.getName(), e.getValue());
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "参数格式不正确：" + e.getName());
    }

    /**
     * 请求体不是合法 JSON / 无法反序列化。
     *
     * <p>归为参数错误：HTTP 200 + 1005，与其它校验失败保持一致，前端无需特判。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.debug("请求体解析失败: {}", e.getClass().getSimpleName());
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "请求体格式不正确");
    }

    /**
     * 路由不存在 → HTTP 404。
     *
     * <p><b>不能</b>落进下面的兜底处理器：那会把「路径写错」伪装成 HTTP 200 + 9001，
     * 既误导前端（把 404 当成系统故障），也让联调时无法定位问题。
     * 契约中「业务失败 HTTP 200」约束的是业务语义，不含路由层错误。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.debug("接口不存在 path={}", e.getResourcePath());
        return Result.fail(ErrorCode.NOT_FOUND);
    }

    /** 请求方法不匹配（如对 GET 接口发 POST）→ HTTP 405。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.debug("请求方法不支持: {}", e.getMethod());
        return Result.fail(ErrorCode.METHOD_NOT_ALLOWED);
    }

    /** Content-Type 不支持（如缺 application/json）→ HTTP 415。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public Result<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.debug("媒体类型不支持: {}", e.getContentType());
        return Result.fail(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    /**
     * 兜底：未预期异常。
     *
     * <p>这里记录**唯一一次**完整堆栈；Service 层不要再对同一异常既记录又抛出。
     * 绝不把 {@code e.getMessage()} 原样返回给客户端（会泄露 SQL 与堆栈细节）。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleException(Exception e) {
        log.error("未预期异常类型={}", e.getClass().getName(), e);
        return Result.fail(ErrorCode.SYSTEM_BUSY);
    }

    /** 汇总字段错误，仅用于 DEBUG 日志。 */
    private String collectFieldErrors(BindException e) {
        return e.getFieldErrors().stream()
                .map(fe -> fe.getField() + "=" + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
    }
}
