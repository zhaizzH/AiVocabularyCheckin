package com.vocab.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocab.common.ErrorCode;
import com.vocab.common.Constants;
import com.vocab.common.Result;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * JWT 鉴权拦截器。
 *
 * <p>契约约束（《接口详细设计》§0 鉴权段）：
 * <ul>
 *   <li>除注册/登录/微信登录外，均需 {@code Authorization: Bearer <token>}</li>
 *   <li>401 = token 缺失/过期/账号禁用</li>
 *   <li>403 = 非管理员访问 {@code /api/admin/**}</li>
 * </ul>
 *
 * <p><b>禁用即时生效</b>：校验 status 需要查库，本拦截器通过
 * {@link UserStatusValidator} 扩展点完成（由阶段 5 的 AuthService 提供实现）。
 * 该接口未实现时（骨架阶段）跳过该校验，保证可独立启动。
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtInterceptor.class);

    private static final String BEARER_PREFIX = "Bearer ";

    /** 无需鉴权的公开路径（契约 §0）。 */
    private static final String[] PUBLIC_PATHS = {
            "/api/client/auth/register",
            "/api/client/auth/login",
            "/api/client/auth/wx-login"
    };

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<UserStatusValidator> statusValidatorProvider;

    public JwtInterceptor(JwtUtil jwtUtil,
                          ObjectMapper objectMapper,
                          ObjectProvider<UserStatusValidator> statusValidatorProvider) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
        this.statusValidatorProvider = statusValidatorProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {

        // CORS 预检请求直接放行（不带 Authorization 头）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        if (isPublicPath(path)) {
            return true;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            log.debug("缺少或格式错误的 Authorization 头 path={}", path);
            writeUnauthorized(response, ErrorCode.TOKEN_INVALID);
            return false;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            writeUnauthorized(response, ErrorCode.TOKEN_INVALID);
            return false;
        }

        Long userId = jwtUtil.getUserId(claims);
        String role = jwtUtil.getRole(claims);
        if (userId == null) {
            log.debug("token 缺少 userId 载荷 path={}", path);
            writeUnauthorized(response, ErrorCode.TOKEN_INVALID);
            return false;
        }

        // 禁用即时生效：每次请求查库校验 status（一期 QPS 低，正确性优先）。
        // 真实实现在阶段 5 接入（UserStatusValidatorImpl）；未接入时 provider 为空，跳过校验。
        UserStatusValidator validator = statusValidatorProvider.getIfAvailable();
        if (validator != null) {
            UserStatusValidator.AccountState state = validator.check(userId);
            if (state == UserStatusValidator.AccountState.DISABLED) {
                log.info("账号已禁用，拒绝访问 userId={} path={}", userId, path);
                writeUnauthorized(response, ErrorCode.USER_DISABLED);
                return false;
            }
            if (state == UserStatusValidator.AccountState.UNKNOWN) {
                // 账号已被删除，但 token 仍在有效期内——按「登录态失效」处理，
                // 引导前端清 token 重新登录。
                log.debug("token 对应的账号不存在 userId={} path={}", userId, path);
                writeUnauthorized(response, ErrorCode.TOKEN_INVALID);
                return false;
            }
        }

        // 管理端必须 ADMIN（契约 §0：403 = 非管理员访问 /api/admin/**）
        if (path.startsWith("/api/admin/") && !Constants.ROLE_ADMIN.equals(role)) {
            log.warn("非管理员访问管理端 userId={} role={} path={}", userId, role, path);
            writeForbidden(response);
            return false;
        }

        UserContext.set(userId, role);
        return true;
    }

    /**
     * 请求结束后清理 ThreadLocal。
     *
     * <p>必须清理：Tomcat 复用工作线程，残留的 UserContext 会泄漏到下一个请求。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private boolean isPublicPath(String path) {
        for (String publicPath : PUBLIC_PATHS) {
            if (publicPath.equals(path)) {
                return true;
            }
        }
        return false;
    }

    /** 401：token 无效/缺失/过期，或账号禁用。响应体仍为 Result 结构。 */
    private void writeUnauthorized(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        writeJson(response, HttpStatus.UNAUTHORIZED, Result.fail(errorCode));
    }

    /** 403：非管理员访问管理端。 */
    private void writeForbidden(HttpServletResponse response) throws IOException {
        writeJson(response, HttpStatus.FORBIDDEN, Result.fail(ErrorCode.FORBIDDEN));
    }

    private void writeJson(HttpServletResponse response, HttpStatus status, Result<?> body)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }
}
