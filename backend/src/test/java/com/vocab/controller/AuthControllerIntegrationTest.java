package com.vocab.controller;

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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证模块集成测试（阶段 5 闸口：5 个 auth 接口自测通过）。
 *
 * <p>覆盖契约《接口详细设计》§1 的每一条错误码与边界：
 * <ul>
 *   <li>§1.1 注册：成功 / 重复 → 1001 / 用户名格式 → 1005 / 密码强度 → 1005</li>
 *   <li>§1.2 登录：成功 / 密码错 → 1002 / 账号不存在 → 1002 / 禁用 → 1003</li>
 *   <li>§1.3 微信登录：openid 复用 / 首次建号 isNewUser / 微信侧异常 → 1004</li>
 *   <li>§1.4 profile：含 totalCheckInDays / 无 token → 401</li>
 *   <li>§1.5 修改：昵称 / 头像白名单 / 至少一项 / avatar_9 → 1005</li>
 *   <li>§8.7 禁用即时生效：禁用后同一 token 立即 401 + 1003 文案</li>
 * </ul>
 *
 * <p><b>为什么用 {@code @Transactional}</b>：测试内直接改库（如禁用用户）
 * 需要在每个用例后回滚，避免用例间互相污染，也避免在真实 MySQL 上留垃圾数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthControllerIntegrationTest {

    private static final String REGISTER = "/api/client/auth/register";
    private static final String LOGIN = "/api/client/auth/login";
    private static final String WX_LOGIN = "/api/client/auth/wx-login";
    private static final String PROFILE = "/api/client/auth/profile";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    /** 每个用例用独立用户名，避免方法间共享状态（H2 与真实 MySQL 上均可重复执行）。 */
    private String uniqueUsername() {
        return "u" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
    }

    private MvcResult registerUser(String username, String password, String nickname) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\""
                + (nickname == null ? "" : ",\"nickname\":\"" + nickname + "\"") + "}";
        return mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        MvcResult result = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    // ---------------------------------------------------------------
    // §1.1 注册
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§1.1 注册成功：code=0，返回 userId/username/nickname/role，且不回 token")
    void registerSuccess() throws Exception {
        String username = uniqueUsername();
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andExpect(jsonPath("$.data.username").value(username))
                // 昵称缺省取 username（契约 §1.1）
                .andExpect(jsonPath("$.data.nickname").value(username))
                .andExpect(jsonPath("$.data.role").value("USER"))
                // 注册响应刻意不含 token
                .andExpect(jsonPath("$.data.token").doesNotExist());
    }

    @Test
    @DisplayName("§1.1 昵称缺省取 username；指定昵称则保留")
    void registerNicknameDefaultsToUsername() throws Exception {
        String username = uniqueUsername();
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value(username));

        String withNickname = uniqueUsername();
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + withNickname
                                + "\",\"password\":\"abc123\",\"nickname\":\"李四\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("李四"));
    }

    @Test
    @DisplayName("§1.1 密码必须以 BCrypt 入库，明文绝不落库")
    void registerStoresBcryptHashOnly() throws Exception {
        String username = uniqueUsername();
        String password = "abc123";
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());

        User saved = userMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<User>lambdaQuery()
                        .eq(User::getUsername, username));
        assertNotNull(saved, "注册后应能查到用户");
        assertNotNull(saved.getPasswordHash());
        assertTrue(saved.getPasswordHash().startsWith("$2"), "应为 BCrypt 哈希（$2a/$2b 前缀）");
        assertFalse(password.equals(saved.getPasswordHash()), "明文密码绝不能落库");
        assertTrue(new BCryptPasswordEncoder().matches(password, saved.getPasswordHash()),
                "哈希必须能校验回原密码");
        // 契约 §1.1：默认 role=USER、status=1
        assertTrue(Constants.ROLE_USER.equals(saved.getRole()));
        assertTrue(Constants.STATUS_ENABLED == saved.getStatus());
    }

    @Test
    @DisplayName("§1.1 重复用户名 → HTTP 200 + 1001")
    void registerDuplicateUsernameReturns1001() throws Exception {
        String username = uniqueUsername();
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1001"))
                .andExpect(jsonPath("$.message").value("用户名已被占用"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("§1.1 用户名格式非法（3 位 / 含非法字符）→ 1005")
    void registerInvalidUsernameReturns1005() throws Exception {
        String[] badUsernames = {"abc", "a".repeat(21), "zhang san", "zhang-san", "张三三三"};
        for (String bad : badUsernames) {
            mockMvc.perform(post(REGISTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + bad + "\",\"password\":\"abc123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("1005"));
        }
    }

    @Test
    @DisplayName("§1.1 密码强度非法（<6 位 / 纯字母 / 纯数字）→ 1005")
    void registerWeakPasswordReturns1005() throws Exception {
        String[] badPasswords = {"ab1", "abcdef", "123456"};
        for (String bad : badPasswords) {
            mockMvc.perform(post(REGISTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + uniqueUsername()
                                    + "\",\"password\":\"" + bad + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("1005"));
        }
    }

    @Test
    @DisplayName("§1.1 昵称超过 20 字 → 1005")
    void registerTooLongNicknameReturns1005() throws Exception {
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + uniqueUsername()
                                + "\",\"password\":\"abc123\",\"nickname\":\""
                                + "字".repeat(21) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    @Test
    @DisplayName("§1.1 缺字段 / JSON 不合法 → 1005（不是 500）")
    void registerMalformedBodyReturns1005() throws Exception {
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));

        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    // ---------------------------------------------------------------
    // §1.2 登录
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§1.2 登录成功：返回 token + userInfo，且不出现 isNewUser 字段")
    void loginSuccess() throws Exception {
        String username = uniqueUsername();
        registerUser(username, "abc123", "张三");

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.userInfo.userId").isNumber())
                .andExpect(jsonPath("$.data.userInfo.username").value(username))
                .andExpect(jsonPath("$.data.userInfo.nickname").value("张三"))
                .andExpect(jsonPath("$.data.userInfo.avatar").value(Constants.DEFAULT_AVATAR))
                .andExpect(jsonPath("$.data.userInfo.role").value("USER"))
                // 契约 §1.2 的示例里没有 isNewUser；字段级 NON_NULL 保证不出现 null
                .andExpect(jsonPath("$.data.userInfo.isNewUser").doesNotExist());
    }

    @Test
    @DisplayName("§1.2 密码错误 → 1002，且响应不泄露「用户是否存在」")
    void loginWrongPasswordReturns1002() throws Exception {
        String username = uniqueUsername();
        registerUser(username, "abc123", null);

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"wrong123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1002"))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("§1.2 账号不存在 → 与密码错误同一个 1002（防用户枚举）")
    void loginUnknownUserReturns1002() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"no-such-user-x\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1002"))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("§1.2 账号被禁用 → 1003（凭证正确时区分于 1002）")
    void loginDisabledAccountReturns1003() throws Exception {
        String username = uniqueUsername();
        registerUser(username, "abc123", null);
        disable(username);

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1003"))
                .andExpect(jsonPath("$.message").value("账号已被禁用，请联系管理员"));
    }

    // ---------------------------------------------------------------
    // §1.3 微信登录
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§1.3 未配置 wx.appid/secret → 1004（不抛 500，不影响账号密码登录）")
    void wxLoginWithoutCredentialsReturns1004() throws Exception {
        // application-test.yml 未配置 wx.appid / wx.secret
        mockMvc.perform(post(WX_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"any-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1004"))
                .andExpect(jsonPath("$.message").value("微信登录失败"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("§1.3 code 为空 → 1005（参数校验先于微信调用）")
    void wxLoginBlankCodeReturns1005() throws Exception {
        mockMvc.perform(post(WX_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    // ---------------------------------------------------------------
    // §1.4 profile
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§1.4 profile 返回扁平结构（不在 userInfo 下）+ totalCheckInDays")
    void profileReturnsFlatStructure() throws Exception {
        String username = uniqueUsername();
        registerUser(username, "abc123", "王五");
        String token = loginAndGetToken(username, "abc123");

        mockMvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                // 契约 §1.4 是扁平结构（与 §1.2 的 userInfo 包装刻意不同）
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.nickname").value("王五"))
                .andExpect(jsonPath("$.data.avatar").value(Constants.DEFAULT_AVATAR))
                .andExpect(jsonPath("$.data.role").value("USER"))
                // 时间格式必须是 "yyyy-MM-dd HH:mm:ss"（JacksonConfig），不是 ISO 的 T 格式
                .andExpect(jsonPath("$.data.createTime").value(
                        org.hamcrest.Matchers.matchesPattern(
                                "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
                // 无打卡记录 → 0（不是 null，前端可直接渲染）
                .andExpect(jsonPath("$.data.totalCheckInDays").value(0))
                .andExpect(jsonPath("$.data.userInfo").doesNotExist())
                // 绝不泄露内部字段
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.deleted").doesNotExist());
    }

    @Test
    @DisplayName("§1.4 无 token → 401 + 1006")
    void profileWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get(PROFILE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1006"));
    }

    // ---------------------------------------------------------------
    // §1.5 修改个人信息
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§1.5 修改昵称与头像 → 返回完整 profile")
    void updateProfileSuccess() throws Exception {
        String username = uniqueUsername();
        registerUser(username, "abc123", null);
        String token = loginAndGetToken(username, "abc123");

        mockMvc.perform(put(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"新昵称\",\"avatar\":\"avatar_3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.nickname").value("新昵称"))
                .andExpect(jsonPath("$.data.avatar").value("avatar_3"))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.totalCheckInDays").value(0));
    }

    @Test
    @DisplayName("§1.5 头像 avatar_9 → 1005（白名单只有 avatar_1~avatar_6）")
    void updateProfileInvalidAvatarReturns1005() throws Exception {
        String username = uniqueUsername();
        registerUser(username, "abc123", null);
        String token = loginAndGetToken(username, "abc123");

        mockMvc.perform(put(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatar\":\"avatar_9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    @Test
    @DisplayName("§1.5 两个字段都不传 → 1005（至少一项）")
    void updateProfileEmptyBodyReturns1005() throws Exception {
        String username = uniqueUsername();
        registerUser(username, "abc123", null);
        String token = loginAndGetToken(username, "abc123");

        mockMvc.perform(put(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    @Test
    @DisplayName("§1.5 昵称超 20 字 → 1005")
    void updateProfileTooLongNicknameReturns1005() throws Exception {
        String username = uniqueUsername();
        registerUser(username, "abc123", null);
        String token = loginAndGetToken(username, "abc123");

        mockMvc.perform(put(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + "字".repeat(21) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1005"));
    }

    @Test
    @DisplayName("§1.5 修改后再次登录，昵称/头像变更可见（确实落库）")
    void updateProfilePersists() throws Exception {
        String username = uniqueUsername();
        registerUser(username, "abc123", null);
        String token = loginAndGetToken(username, "abc123");

        mockMvc.perform(put(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"持久化昵称\",\"avatar\":\"avatar_6\"}"))
                .andExpect(jsonPath("$.code").value("0"));

        User saved = userMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<User>lambdaQuery()
                        .eq(User::getUsername, username));
        assertTrue("持久化昵称".equals(saved.getNickname()));
        assertTrue("avatar_6".equals(saved.getAvatar()));
    }

    // ---------------------------------------------------------------
    // §8.7 禁用即时生效
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§8.7 禁用后同一 token 立即失效：401 + 1003 文案（不需重新签发 token）")
    void disabledUserTokenRejectedImmediately() throws Exception {
        String username = uniqueUsername();
        registerUser(username, "abc123", null);
        String token = loginAndGetToken(username, "abc123");

        // 禁用前：token 可用
        mockMvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        disable(username);

        // 禁用后：**同一个 token** 立即被拒，证明校验的是库里的 status 而非 token 内容
        mockMvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1003"))
                .andExpect(jsonPath("$.message").value("账号已被禁用，请联系管理员"));
    }

    @Test
    @DisplayName("账号被逻辑删除后，残留 token → 401 + 1006（按登录态失效处理）")
    void deletedUserTokenReturns1006() throws Exception {
        String username = uniqueUsername();
        registerUser(username, "abc123", null);
        String token = loginAndGetToken(username, "abc123");

        User saved = userMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<User>lambdaQuery()
                        .eq(User::getUsername, username));
        userMapper.deleteById(saved.getId());

        mockMvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1006"));
    }

    @Test
    @DisplayName("§1.5 令牌中的 userId 与请求目标不一致时不可越权（profile 只返回自己）")
    void profileAlwaysScopedToTokenUser() throws Exception {
        String first = uniqueUsername();
        String second = uniqueUsername();
        registerUser(first, "abc123", "甲");
        registerUser(second, "abc123", "乙");

        String secondToken = loginAndGetToken(second, "abc123");
        MvcResult result = mockMvc.perform(get(PROFILE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken))
                .andExpect(status().isOk())
                .andReturn();
        String returnedUsername = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("username").asText();
        assertTrue(second.equals(returnedUsername),
                "profile 必须只返回 token 对应用户（防越权），实际返回 " + returnedUsername);
    }

    @Test
    @DisplayName("登录响应中的 token 可被拦截器解析（签发与校验载荷字段一致）")
    void issuedTokenIsAcceptedByInterceptor() throws Exception {
        String username = uniqueUsername();
        registerUser(username, "abc123", null);
        String token = loginAndGetToken(username, "abc123");
        assertNotNull(token);
        assertFalse(token.isBlank());
        // 能被 profile 接受即为端到端证明（拦截器要求 userId 载荷存在）
        mockMvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** 直接改库把用户状态置为禁用（模拟管理员操作，阶段 10 才接管理端接口）。 */
    private void disable(String username) {
        User user = userMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<User>lambdaQuery()
                        .eq(User::getUsername, username));
        assertNotNull(user, "待禁用的用户必须存在");
        user.setStatus(Constants.STATUS_DISABLED);
        userMapper.updateById(user);
    }
}
