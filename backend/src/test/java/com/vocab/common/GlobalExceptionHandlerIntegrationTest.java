package com.vocab.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vocab.config.JwtUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理器集成测试。
 *
 * <p>锁定契约中「业务失败 HTTP 200 + code」的核心约定：
 * 前端只看 {@code code}，一旦 HTTP 状态码或响应结构漂移，
 * 小程序所有错误提示都会静默失效。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerIntegrationTest {

    private static final String PROBE = "/api/client/__test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    /** 携带合法 token 以通过拦截器，专注验证异常处理本身。 */
    private String token() {
        return jwtUtil.generateToken(999L, "USER");
    }

    @Test
    @DisplayName("合法请求应返回 code=0 与数据")
    void successReturnsZero() throws Exception {
        mockMvc.perform(post(PROBE + "/ok")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data").value("probe"));
    }

    @Test
    @DisplayName("请求体校验失败 → HTTP 200 + 1005 + 字段提示")
    void validationFailureReturnsOkWith1005() throws Exception {
        mockMvc.perform(post(PROBE + "/validate-body")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"))
                .andExpect(jsonPath("$.message").value("用户名不能为空"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("超长字段 → 返回该字段的长度提示")
    void validationLengthMessage() throws Exception {
        String longName = "x".repeat(30);
        mockMvc.perform(post(PROBE + "/validate-body")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + longName + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"))
                .andExpect(jsonPath("$.message").value("用户名最多 20 字"));
    }

    @Test
    @DisplayName("业务异常 → HTTP 200 + 业务错误码")
    void bizExceptionReturnsOkWithBusinessCode() throws Exception {
        mockMvc.perform(post(PROBE + "/biz-error")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("3001"))
                .andExpect(jsonPath("$.message").value("今日已打卡"));
    }

    @Test
    @DisplayName("未预期异常 → HTTP 200 + 9001，且不泄露内部信息")
    void unexpectedExceptionHidesInternals() throws Exception {
        String body = mockMvc.perform(post(PROBE + "/unexpected-error")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("9001"))
                .andExpect(jsonPath("$.message").value("系统繁忙"))
                .andReturn().getResponse().getContentAsString();

        // 关键安全断言：内部异常消息绝不能出现在响应里
        Assertions.assertFalse(body.contains("数据库"), "响应体泄露了内部异常细节: " + body);
    }

    @Test
    @DisplayName("方法参数约束失败 → HTTP 200 + 1005 + 参数提示")
    void paramConstraintReturnsOkWith1005() throws Exception {
        mockMvc.perform(post(PROBE + "/validate-param")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                        .param("id", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    @Test
    @DisplayName("缺少必填参数 → HTTP 200 + 1005")
    void missingParamReturnsOkWith1005() throws Exception {
        mockMvc.perform(post(PROBE + "/validate-param")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    @Test
    @DisplayName("非法 JSON → HTTP 200 + 1005")
    void malformedJsonReturnsOkWith1005() throws Exception {
        mockMvc.perform(post(PROBE + "/validate-body")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not valid json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"))
                .andExpect(jsonPath("$.message").value("请求体格式不正确"));
    }
}
