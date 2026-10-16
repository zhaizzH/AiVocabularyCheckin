package com.vocab.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtInterceptorIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    /** 阶段 2 尚无任何 Controller，已鉴权通过的请求会落到「接口不存在」的 404。 */
    private static final int NO_HANDLER = 404;

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
    @DisplayName("公开路径（注册/登录/微信登录）必须放行，不返回 401")
    void publicPathsAreAllowed() throws Exception {
        String[] publicPaths = {
                "/api/client/auth/register",
                "/api/client/auth/login",
                "/api/client/auth/wx-login"
        };
        for (String path : publicPaths) {
            int status = mockMvc.perform(post(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn().getResponse().getStatus();
            assertEquals(NO_HANDLER, status,
                    "公开路径 " + path + " 应放行至控制器层，实际状态码 " + status + "（401 说明白名单失效）");
        }
    }

    @Test
    @DisplayName("普通用户访问管理端接口 → 403")
    void userRoleForbiddenOnAdminPath() throws Exception {
        String userToken = jwtUtil.generateToken(100L, "USER");
        mockMvc.perform(get("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1007"));
    }

    @Test
    @DisplayName("管理员访问管理端接口 → 通过鉴权（落到 404 证明拦截器已放行）")
    void adminRoleAllowedOnAdminPath() throws Exception {
        String adminToken = jwtUtil.generateToken(1L, "ADMIN");
        mockMvc.perform(get("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("普通用户访问客户端接口 → 通过鉴权")
    void userRoleAllowedOnClientPath() throws Exception {
        String userToken = jwtUtil.generateToken(100L, "USER");
        mockMvc.perform(get("/api/client/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isNotFound());
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
        String token = jwtUtil.generateToken(555L, "USER");
        mockMvc.perform(get("/api/client/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
        assertNull(UserContext.get(), "请求结束后 UserContext 必须被清理");
    }

    @Test
    @DisplayName("404 必须保留 HTTP 404 语义，不能被兜底处理器吞成 200")
    void notFoundKeepsHttpStatus() throws Exception {
        String token = jwtUtil.generateToken(100L, "USER");
        mockMvc.perform(get("/api/client/definitely-not-a-real-endpoint")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("9002"));
    }
}
