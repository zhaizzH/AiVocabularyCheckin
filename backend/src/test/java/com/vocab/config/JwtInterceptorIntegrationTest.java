package com.vocab.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocab.common.Constants;
import com.vocab.entity.User;
import com.vocab.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权拦截器 + 全局异常处理器的集成测试。
 *
 * <p>覆盖契约中最容易回归的几条：
 * <ul>
 *   <li>无 token → 401 + 1006（响应体仍为 Result 结构，不能是 Spring 默认错误页）</li>
 *   <li>公开路径放行（不因缺 token 被 401）</li>
 *   <li>非管理员访问 /api/admin/** → 403</li>
 *   <li>ADMIN 访问管理端 → 放行（此处落到 404，证明已过鉴权）</li>
 *   <li>CORS 预检放行</li>
 *   <li>{@link UserContext} 不残留（线程复用不串号）</li>
 * </ul>
 *
 * <p><b>阶段 5 变化</b>：{@link UserStatusValidator} 已由真实查库实现接管，
 * 因此凡是要「通过鉴权」的用例都必须先在库里建一个真实用户——伪造的
 * userId 会被判为 {@code UNKNOWN} 并得到 401。这本身就是「禁用即时生效」
 * 依赖的能力，本类顺带锁定它。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class JwtInterceptorIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserMapper userMapper;

    /** 已存在的用户访问 {PROFILE} 不再落到 404，而是命中真实的 AuthController。 */
    private static final int PROFILE_OK = 200;

    /** 管理端此时仍无任何 Controller，鉴权通过后落到「接口不存在」的 404。 */
    private static final int NO_HANDLER = 404;

    private User newUser(String role) {
        User user = new User();
        user.setUsername("it_" + System.nanoTime());
        user.setPasswordHash("$2a$10$fakeHashForInterceptorTest");
        user.setNickname("拦截器测试");
        user.setAvatar(Constants.DEFAULT_AVATAR);
        user.setRole(role);
        user.setStatus(Constants.STATUS_ENABLED);
        userMapper.insert(user);
        return user;
    }

    private String tokenFor(User user) {
        return jwtUtil.generateToken(user.getId(), user.getRole());
    }

    @Test
    @DisplayName("无 token 访问受保护接口 → 401，且响应体是 Result 结构")
    void missingTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/client/auth/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1006"))
                .andExpect(jsonPath("$.message").value("登录状态已失效，请重新登录"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("畸形 token → 401（不是 500）")
    void malformedTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/client/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.real.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1006"));
    }

    @Test
    @DisplayName("缺少 Bearer 前缀 → 401")
    void missingBearerPrefixReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/client/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "raw-token-without-prefix"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1006"));
    }

    @Test
    @DisplayName("签名被篡改的 token → 401 + 1006")
    void tamperedTokenReturnsUnauthorized() throws Exception {
        User user = newUser(Constants.ROLE_USER);
        String token = tokenFor(user);
        // 改 payload 段首字符：签名输入必然变化（改签名段末字符可能因 base64 填充位被丢弃而无效）
        String[] parts = token.split("\\.");
        char first = parts[1].charAt(0);
        char replaced = first == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + replaced + parts[1].substring(1) + "." + parts[2];

        mockMvc.perform(get("/api/client/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1006"));
    }

    @Test
    @DisplayName("公开路径（注册/登录/微信登录）必须放行，不返回 401")
    void publicPathsAreAllowed() throws Exception {
        // 空请求体 → 参数校验失败（1005），但**绝不能**是 401（说明白名单失效）
        String[] publicPaths = {
                "/api/client/auth/register",
                "/api/client/auth/login",
                "/api/client/auth/wx-login"
        };
        for (String path : publicPaths) {
            mockMvc.perform(post(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("1005"));
        }
    }

    @Test
    @DisplayName("普通用户访问管理端接口 → 403")
    void userRoleForbiddenOnAdminPath() throws Exception {
        User user = newUser(Constants.ROLE_USER);
        mockMvc.perform(get("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1007"));
    }

    @Test
    @DisplayName("管理员访问管理端接口 → 通过鉴权（落到 404 证明拦截器已放行）")
    void adminRoleAllowedOnAdminPath() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        mockMvc.perform(get("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("已登录用户访问客户端接口 → 通过鉴权（命中真实 AuthController）")
    void userRoleAllowedOnClientPath() throws Exception {
        User user = newUser(Constants.ROLE_USER);
        mockMvc.perform(get("/api/client/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.userId").value(user.getId()));
    }

    @Test
    @DisplayName("CORS 预检请求必须放行（浏览器不携带 Authorization）")
    void corsPreflightAllowed() throws Exception {
        mockMvc.perform(options("/api/client/auth/profile")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("认证失败时不得残留 UserContext（线程复用不串号）")
    void userContextNotLeakedOnFailure() throws Exception {
        mockMvc.perform(get("/api/client/auth/profile"))
                .andExpect(status().isUnauthorized());
        assertNull(UserContext.get(), "鉴权失败路径也必须清理 UserContext");
        assertNull(UserContext.getUserId());
    }

    @Test
    @DisplayName("鉴权通过后 UserContext 必须被清理（ThreadLocal 不泄漏）")
    void userContextClearedAfterSuccess() throws Exception {
        User user = newUser(Constants.ROLE_USER);
        mockMvc.perform(get("/api/client/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
                .andExpect(status().isOk());
        assertNull(UserContext.get(), "请求结束后 UserContext 必须被清理");
    }

    @Test
    @DisplayName("404 必须保留 HTTP 404 语义，不能被兜底处理器吞成 200")
    void notFoundKeepsHttpStatus() throws Exception {
        User user = newUser(Constants.ROLE_USER);
        mockMvc.perform(get("/api/client/definitely-not-a-real-endpoint")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("9002"));
    }

    // ---------------------------------------------------------------
    // 阶段 5 新增：禁用即时生效（UserStatusValidator 真实实现）
    // ---------------------------------------------------------------

    @Test
    @DisplayName("禁用用户（status=0）→ 其 token 立即被拒，401 + 1003（查库而非信 token）")
    void disabledUserRejectedWith1003() throws Exception {
        User user = newUser(Constants.ROLE_USER);
        String token = tokenFor(user);

        // 禁用前可用
        mockMvc.perform(get("/api/client/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        user.setStatus(Constants.STATUS_DISABLED);
        userMapper.updateById(user);

        // 同一个 token，禁用后立即失败
        mockMvc.perform(get("/api/client/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1003"))
                .andExpect(jsonPath("$.message").value("账号已被禁用，请联系管理员"));
    }

    @Test
    @DisplayName("账号不存在（或已逻辑删除）→ 401 + 1006，而非 1003")
    void unknownUserRejectedWith1006() throws Exception {
        // 伪造一个不存在的 userId 的 token
        String token = jwtUtil.generateToken(199_999_999L, Constants.ROLE_USER);
        mockMvc.perform(get("/api/client/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1006"));
    }

    @Test
    @DisplayName("逻辑删除的用户 → 401 + 1006（不能冒充 1003 的文案）")
    void softDeletedUserRejectedWith1006() throws Exception {
        User user = newUser(Constants.ROLE_USER);
        String token = tokenFor(user);
        userMapper.deleteById(user.getId());

        mockMvc.perform(get("/api/client/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1006"));
    }

    @Test
    @DisplayName("未被拦截器覆盖的路径不受影响（验证拦截器只作用于 /api/**）")
    void nonApiPathNotIntercepted() throws Exception {
        int status = mockMvc.perform(get("/definitely-not-api"))
                .andReturn().getResponse().getStatus();
        // 非 /api 路径不被拦截器处理，直接 404；若被拦成 401 说明配置范围写宽了
        assertTrue(status == NO_HANDLER || status == PROFILE_OK,
                "非 /api 路径不应被鉴权拦截器处理，实际状态码 " + status);
    }

    @Test
    @DisplayName("ADMIN 自己也是库中真实用户，禁用后管理端同样被拒（不能只校验角色）")
    void disabledAdminAlsoRejected() throws Exception {
        User admin = newUser(Constants.ROLE_ADMIN);
        String token = tokenFor(admin);

        mockMvc.perform(get("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());

        admin.setStatus(Constants.STATUS_DISABLED);
        userMapper.updateById(admin);

        mockMvc.perform(get("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1003"));
    }

    @Test
    @DisplayName("token 缺少 role 载荷时，管理端必须按 USER 处理（不能默认放行）")
    void tokenWithoutRoleCannotAccessAdmin() throws Exception {
        User user = newUser(Constants.ROLE_USER);
        // 用同一个 JwtUtil 签发，但角色传 null，模拟载荷缺失
        String token = jwtUtil.generateToken(user.getId(), null);
        mockMvc.perform(get("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1007"));
    }

    @Test
    @DisplayName("UserContext 在请求结束后必须清空（userId 不在请求间串号）")
    void userContextNotSharedBetweenRequests() throws Exception {
        User first = newUser(Constants.ROLE_USER);
        User second = newUser(Constants.ROLE_USER);

        mockMvc.perform(get("/api/client/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(first.getId()));

        // 第二个请求必须拿到自己的 userId，而不是上一个请求残留的
        mockMvc.perform(get("/api/client/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(second.getId()));

        assertNull(UserContext.get());
    }
}
