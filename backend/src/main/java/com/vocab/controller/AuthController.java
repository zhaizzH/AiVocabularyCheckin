package com.vocab.controller;

import com.vocab.common.Result;
import com.vocab.config.UserContext;
import com.vocab.dto.request.LoginRequest;
import com.vocab.dto.request.RegisterRequest;
import com.vocab.dto.request.UpdateProfileRequest;
import com.vocab.dto.request.WxLoginRequest;
import com.vocab.dto.response.LoginResponse;
import com.vocab.dto.response.ProfileResponse;
import com.vocab.dto.response.RegisterResponse;
import com.vocab.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证模块接口（《接口详细设计》§1）。
 *
 * <p>Controller 只做三件事：参数校验（{@code @Valid}）、调用一个 Service 方法、
 * 包装为 {@link Result}。业务规则全部在 {@link AuthService} 中
 * （quality-guidelines.md「Controller 不写业务逻辑」）。
 *
 * <p>{@code /register}、{@code /login}、{@code /wx-login} 是公开路径，
 * 由 {@code JwtInterceptor} 白名单放行（契约 §0）；
 * {@code /profile} 两个接口需要登录，当前用户 id 取自 {@link UserContext}。
 */
@RestController
@RequestMapping("/api/client/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** §1.1 注册。 */
    @PostMapping("/register")
    public Result<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    /** §1.2 账号密码登录（管理员也走此接口，由 role 区分）。 */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    /** §1.3 微信登录（code2Session 真实调用）。 */
    @PostMapping("/wx-login")
    public Result<LoginResponse> wxLogin(@Valid @RequestBody WxLoginRequest request) {
        return Result.ok(authService.wxLogin(request));
    }

    /** §1.4 获取个人信息。 */
    @GetMapping("/profile")
    public Result<ProfileResponse> profile() {
        return Result.ok(authService.profile(UserContext.getUserId()));
    }

    /** §1.5 修改个人信息（昵称 / 内置头像，至少一项）。 */
    @PutMapping("/profile")
    public Result<ProfileResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return Result.ok(authService.updateProfile(UserContext.getUserId(), request));
    }
}
