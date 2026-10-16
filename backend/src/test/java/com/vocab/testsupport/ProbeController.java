package com.vocab.testsupport;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import com.vocab.common.BizException;
import com.vocab.common.ErrorCode;
import com.vocab.common.Result;

/**
 * 仅用于测试的探针控制器。
 *
 * <p>阶段 2 尚无任何业务 Controller，但全局异常处理器与拦截器已经需要被验证。
 * 本类提供最小端点，让「参数校验失败」「业务异常」「成功响应」三条路径
 * 都能被真实触发，而不是靠 mock 伪造。
 *
 * <p>位于 {@code src/test}，不会被打进生产 jar。
 */
@RestController
@RequestMapping("/api/client/__test")
@Validated
public class ProbeController {

    /** 触发 @Valid 请求体校验失败。 */
    @PostMapping("/validate-body")
    public Result<String> validateBody(@Valid @RequestBody ProbeRequest request) {
        return Result.ok(request.getName());
    }

    /** 触发业务异常（验证 BizException → 200 + 对应 code）。 */
    @PostMapping("/biz-error")
    public Result<Void> bizError() {
        throw new BizException(ErrorCode.ALREADY_CHECKED_IN);
    }

    /** 触发未预期异常（验证兜底 → 200 + 9001，且不泄露内部信息）。 */
    @PostMapping("/unexpected-error")
    public Result<Void> unexpectedError() {
        throw new IllegalStateException("内部数据库细节不应泄露给客户端");
    }

    /** 触发方法参数约束失败（@RequestParam + @Validated）。 */
    @PostMapping("/validate-param")
    public Result<String> validateParam(@RequestParam @NotBlank(message = "id 不能为空") String id) {
        return Result.ok(id);
    }

    /** 成功响应探针。 */
    @PostMapping("/ok")
    public Result<String> ok() {
        return Result.ok("probe");
    }

    /** 请求体载体。 */
    public static class ProbeRequest {

        @NotBlank(message = "用户名不能为空")
        @Size(max = 20, message = "用户名最多 20 字")
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
