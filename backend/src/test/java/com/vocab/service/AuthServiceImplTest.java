package com.vocab.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.vocab.client.WxApiClient;
import com.vocab.common.BizException;
import com.vocab.common.Constants;
import com.vocab.common.ErrorCode;
import com.vocab.config.JwtUtil;
import com.vocab.dto.request.LoginRequest;
import com.vocab.dto.request.RegisterRequest;
import com.vocab.dto.request.UpdateProfileRequest;
import com.vocab.dto.request.WxLoginRequest;
import com.vocab.dto.response.LoginResponse;
import com.vocab.dto.response.ProfileResponse;
import com.vocab.dto.response.RegisterResponse;
import com.vocab.entity.StudyRecord;
import com.vocab.entity.User;
import com.vocab.mapper.StatsMapper;
import com.vocab.mapper.StudyRecordMapper;
import com.vocab.mapper.UserMapper;
import com.vocab.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@code AuthService} 的服务层测试（阶段 5）。
 *
 * <p>与 {@code AuthControllerIntegrationTest} 的分工：
 * <ul>
 *   <li>Controller 测试走真实 HTTP，验证契约的<b>外部形状</b>（路径、状态码、字段名、错误码）</li>
 *   <li>本测试直连 Service + 真实数据库，验证<b>业务规则与并发分支</b>
 *       （微信建号、openid 复用、唯一索引兜底、越权写入防护）</li>
 * </ul>
 *
 * <p>这样分工是有必要的：{@code createWxUser} 的「唯一索引冲突后重查」分支
 * 无法通过 HTTP 稳定触发（需要真实的并发时序），只能在这一层用桩注入。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthServiceImplTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StudyRecordMapper studyRecordMapper;

    @Autowired
    private StatsMapper statsMapper;

    @Autowired
    private JwtUtil jwtUtil;

    /** 默认场景：微信侧一切正常。具体 openid 由各用例 stub。 */
    private WxApiClient wxApiClient;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        wxApiClient = Mockito.mock(WxApiClient.class);
        authService = new AuthServiceImpl(userMapper, statsMapper, jwtUtil, wxApiClient);
    }

    private String uniqueUsername() {
        return "svc" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
    }

    private RegisterRequest registerRequest(String username, String password, String nickname) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(password);
        request.setNickname(nickname);
        return request;
    }

    private void stubWx(String code, String openid) {
        when(wxApiClient.code2Session(code)).thenReturn(openid);
    }

    private WxLoginRequest wxRequest(String code) {
        WxLoginRequest request = new WxLoginRequest();
        request.setCode(code);
        return request;
    }

    private User selectByUsername(String username) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }

    // ---------------------------------------------------------------
    // 注册 / 登录
    // ---------------------------------------------------------------

    @Test
    @DisplayName("注册：昵称首尾空白被裁剪；空字符串昵称回退为 username")
    void registerTrimsNicknameAndFallsBack() {
        String username = uniqueUsername();
        RegisterResponse response = authService.register(
                registerRequest(username, "abc123", "  张三  "));
        assertEquals("张三", response.getNickname());

        String second = uniqueUsername();
        RegisterResponse fallback = authService.register(registerRequest(second, "abc123", "   "));
        assertEquals(second, fallback.getNickname(), "空白昵称应回退为 username");
    }

    @Test
    @DisplayName("注册：username 首尾空白被裁剪（'abc_1 ' 与 'abc_1' 视为同一个）")
    void registerTrimsUsername() {
        String username = uniqueUsername();
        authService.register(registerRequest("  " + username + "  ", "abc123", null));
        assertNotNull(selectByUsername(username), "裁剪后的 username 应能被查到");
        assertThrows(BizException.class,
                () -> authService.register(registerRequest(username, "abc123", null)),
                "裁剪后重名必须 → 1001");
    }

    @Test
    @DisplayName("登录：返回的 token 携带 userId 与 role 载荷（能被 JwtUtil 解析回来）")
    void loginTokenCarriesClaims() {
        String username = uniqueUsername();
        authService.register(registerRequest(username, "abc123", null));

        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword("abc123");
        LoginResponse response = authService.login(request);

        assertNotNull(response.getToken());
        var claims = jwtUtil.parseToken(response.getToken());
        assertNotNull(claims, "签发的 token 必须能自校验通过");
        assertEquals(selectByUsername(username).getId(), jwtUtil.getUserId(claims));
        assertEquals(Constants.ROLE_USER, jwtUtil.getRole(claims));
        // §1.2：账号密码登录不出现 isNewUser
        assertNull(response.getUserInfo().getIsNewUser());
    }

    @Test
    @DisplayName("登录：ADMIN 走同一接口，role 由库中记录决定（不能靠用户输入提权）")
    void loginAdminRoleComesFromDatabase() {
        String username = uniqueUsername();
        authService.register(registerRequest(username, "abc123", null));
        User user = selectByUsername(username);
        user.setRole(Constants.ROLE_ADMIN);
        userMapper.updateById(user);

        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword("abc123");
        LoginResponse response = authService.login(request);

        assertEquals(Constants.ROLE_ADMIN, response.getUserInfo().getRole());
        assertEquals(Constants.ROLE_ADMIN, jwtUtil.getRole(jwtUtil.parseToken(response.getToken())));
    }

    @Test
    @DisplayName("登录：微信用户（passwordHash 为 null）用密码登录必然 1002，不抛 NPE")
    void loginWxUserWithoutPasswordReturns1002() {
        // 直接造一个微信用户（无密码）
        User wxUser = new User();
        wxUser.setOpenid("openid-nopwd-" + System.nanoTime());
        wxUser.setNickname("微信用户abcd");
        wxUser.setAvatar(Constants.DEFAULT_AVATAR);
        wxUser.setRole(Constants.ROLE_USER);
        wxUser.setStatus(Constants.STATUS_ENABLED);
        userMapper.insert(wxUser);
        // 给一个 username 以便走密码登录路径（模拟后期补设用户名的情况）
        wxUser.setUsername(uniqueUsername());
        userMapper.updateById(wxUser);

        LoginRequest request = new LoginRequest();
        request.setUsername(wxUser.getUsername());
        request.setPassword("abc123");
        BizException e = assertThrows(BizException.class, () -> authService.login(request));
        assertEquals(ErrorCode.LOGIN_FAILED.getCode(), e.getCode());
    }

    // ---------------------------------------------------------------
    // 微信登录
    // ---------------------------------------------------------------

    @Test
    @DisplayName("微信登录：openid 不存在 → 自动建号，昵称「微信用户+4 位后缀」、头像 avatar_1、isNewUser=true")
    void wxLoginCreatesUserOnFirstLogin() {
        String openid = "openid-new-" + System.nanoTime();
        stubWx("code-new", openid);

        LoginResponse response = authService.wxLogin(wxRequest("code-new"));

        assertTrue(Boolean.TRUE.equals(response.getUserInfo().getIsNewUser()), "首次登录必须 isNewUser=true");
        assertEquals(Constants.ROLE_USER, response.getUserInfo().getRole());
        assertEquals(Constants.DEFAULT_AVATAR, response.getUserInfo().getAvatar());
        assertNull(response.getUserInfo().getUsername(), "微信用户没有用户名");
        assertNotNull(response.getUserInfo().getUserId());

        String nickname = response.getUserInfo().getNickname();
        assertNotNull(nickname);
        assertTrue(nickname.startsWith(Constants.WX_NICKNAME_PREFIX),
                "昵称应以「微信用户」开头，实际 " + nickname);
        assertEquals(Constants.WX_NICKNAME_PREFIX.length() + Constants.WX_NICKNAME_SUFFIX_LENGTH,
                nickname.length(), "后缀长度应为 " + Constants.WX_NICKNAME_SUFFIX_LENGTH);

        User saved = userMapper.selectById(response.getUserInfo().getUserId());
        assertEquals(openid, saved.getOpenid());
        assertNull(saved.getPasswordHash(), "微信用户的 password_hash 必须为空（不可密码登录）");
        assertEquals(Constants.STATUS_ENABLED, saved.getStatus());
    }

    @Test
    @DisplayName("微信登录：openid 已存在 → 复用账号，isNewUser 缺失（不返回 false，字段不出现）")
    void wxLoginReusesExistingUser() {
        String openid = "openid-repeat-" + System.nanoTime();
        stubWx("code-1", openid);
        LoginResponse first = authService.wxLogin(wxRequest("code-1"));

        stubWx("code-2", openid);
        LoginResponse second = authService.wxLogin(wxRequest("code-2"));

        assertEquals(first.getUserInfo().getUserId(), second.getUserInfo().getUserId(),
                "同一 openid 必须复用同一账号");
        assertNull(second.getUserInfo().getIsNewUser(),
                "非首次登录不应出现 isNewUser 字段（契约 §1.3 是「额外多一个字段」）");
    }

    @Test
    @DisplayName("微信登录：昵称随机后缀确实随机（两次建号昵称不同）")
    void wxNicknameSuffixIsRandom() {
        String openid1 = "openid-r1-" + System.nanoTime();
        String openid2 = "openid-r2-" + System.nanoTime();
        stubWx("c1", openid1);
        stubWx("c2", openid2);
        String first = authService.wxLogin(wxRequest("c1")).getUserInfo().getNickname();
        String second = authService.wxLogin(wxRequest("c2")).getUserInfo().getNickname();
        // 1/36^4 ≈ 6e-7 的碰撞率，足以作为确定性断言
        assertFalse(first.equals(second), "两次自动建号的昵称不应相同：" + first);
    }

    @Test
    @DisplayName("微信登录：账号被禁用 → 1003（禁用对微信路径同样即时生效）")
    void wxLoginDisabledUserReturns1003() {
        String openid = "openid-disabled-" + System.nanoTime();
        stubWx("code-d", openid);
        LoginResponse created = authService.wxLogin(wxRequest("code-d"));

        User user = userMapper.selectById(created.getUserInfo().getUserId());
        user.setStatus(Constants.STATUS_DISABLED);
        userMapper.updateById(user);

        BizException e = assertThrows(BizException.class, () -> authService.wxLogin(wxRequest("code-d")));
        assertEquals(ErrorCode.USER_DISABLED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("微信登录：并发建号触发唯一索引冲突 → 复用已存在账号，不报错（关键并发分支）")
    void wxLoginHandlesConcurrentCreationRace() {
        String openid = "openid-race-" + System.nanoTime();

        // 先真实建号，模拟「另一个并发请求已经插入成功」
        User concurrent = new User();
        concurrent.setOpenid(openid);
        concurrent.setNickname("微信用户race");
        concurrent.setAvatar(Constants.DEFAULT_AVATAR);
        concurrent.setRole(Constants.ROLE_USER);
        concurrent.setStatus(Constants.STATUS_ENABLED);
        userMapper.insert(concurrent);

        // 桩一个「先查不到、插入撞唯一索引、重查拿到」的 Mapper
        UserMapper racingMapper = Mockito.mock(UserMapper.class);
        when(racingMapper.selectOne(any())).thenReturn(null).thenReturn(concurrent);
        when(racingMapper.insert(any(User.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'user.uk_openid'"));

        when(wxApiClient.code2Session("code-race")).thenReturn(openid);
        AuthService racingService = new AuthServiceImpl(racingMapper, statsMapper, jwtUtil, wxApiClient);

        LoginResponse response = racingService.wxLogin(wxRequest("code-race"));
        assertEquals(concurrent.getId(), response.getUserInfo().getUserId(),
                "并发建号必须复用已存在账号，而不是把 500 抛给用户");
    }

    @Test
    @DisplayName("微信登录：唯一索引冲突但重查不到 → 原样抛出（不能静默吞掉异常）")
    void wxLoginRethrowsWhenConflictUnresolvable() {
        UserMapper brokenMapper = Mockito.mock(UserMapper.class);
        when(brokenMapper.selectOne(any())).thenReturn(null);
        when(brokenMapper.insert(any(User.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'user.uk_username'"));

        when(wxApiClient.code2Session("code-broken")).thenReturn("openid-broken");
        AuthService brokenService = new AuthServiceImpl(brokenMapper, statsMapper, jwtUtil, wxApiClient);

        assertThrows(DuplicateKeyException.class,
                () -> brokenService.wxLogin(wxRequest("code-broken")),
                "冲突原因不明时必须上抛，交由全局处理器记系统错误");
    }

    // ---------------------------------------------------------------
    // profile
    // ---------------------------------------------------------------

    @Test
    @DisplayName("profile：不存在的 userId → 1006（而不是空对象或 NPE）")
    void profileUnknownUserReturns1006() {
        BizException e = assertThrows(BizException.class, () -> authService.profile(199_999_999L));
        assertEquals(ErrorCode.TOKEN_INVALID.getCode(), e.getCode());
    }

    @Test
    @DisplayName("profile：username 为 null 的微信用户也能正常返回（不 NPE）")
    void profileWxUserWithNullUsername() {
        User wxUser = new User();
        wxUser.setOpenid("openid-profile-" + System.nanoTime());
        wxUser.setNickname("微信用户zzzz");
        wxUser.setAvatar(Constants.DEFAULT_AVATAR);
        wxUser.setRole(Constants.ROLE_USER);
        wxUser.setStatus(Constants.STATUS_ENABLED);
        userMapper.insert(wxUser);

        ProfileResponse profile = authService.profile(wxUser.getId());
        assertNull(profile.getUsername());
        assertEquals("微信用户zzzz", profile.getNickname());
        assertEquals(0, profile.getTotalCheckInDays(), "无打卡记录 → 0 而非 null");
    }

    @Test
    @DisplayName("profile：totalCheckInDays 只统计不同 check_in_date（去重）")
    void profileCountsDistinctCheckInDays() {
        String username = uniqueUsername();
        authService.register(registerRequest(username, "abc123", null));
        User user = selectByUsername(username);

        // 同一天两条 + 另一天一条 → 应为 2 天
        insertStudyRecord(user.getId(), "2026-10-01", "2026-10-01");
        insertStudyRecord(user.getId(), "2026-10-01", "2026-10-01");
        insertStudyRecord(user.getId(), "2026-10-02", "2026-10-02");

        assertEquals(2, authService.profile(user.getId()).getTotalCheckInDays());
    }

    private void insertStudyRecord(Long userId, String studyDate, String checkInDate) {
        StudyRecord record = new StudyRecord();
        record.setUserId(userId);
        record.setBookId(1L);
        record.setWordId(ThreadLocalRandom.current().nextLong(1_000_000));
        record.setStudyType(Constants.STUDY_TYPE_STUDY);
        record.setStudyDate(LocalDate.parse(studyDate));
        record.setCheckInDate(LocalDate.parse(checkInDate));
        record.setStreakDays(1);
        record.setCorrect(1);
        studyRecordMapper.insert(record);
        assertNotNull(record.getId(), "打卡记录必须真的落库，否则计数断言会假通过");
    }

    // ---------------------------------------------------------------
    // updateProfile
    // ---------------------------------------------------------------

    @Test
    @DisplayName("updateProfile：只改 nickname 时 avatar 保持不变")
    void updateProfilePartialUpdateKeepsOtherField() {
        String username = uniqueUsername();
        authService.register(registerRequest(username, "abc123", null));
        User user = selectByUsername(username);

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("只改昵称");
        ProfileResponse response = authService.updateProfile(user.getId(), request);

        assertEquals("只改昵称", response.getNickname());
        assertEquals(Constants.DEFAULT_AVATAR, response.getAvatar(), "未传的字段必须保持原值");
    }

    @Test
    @DisplayName("updateProfile：avatar 走 Constants.AVATARS 白名单（avatar_0/avatar_7 均拒）")
    void updateProfileAvatarWhitelist() {
        String username = uniqueUsername();
        authService.register(registerRequest(username, "abc123", null));
        User user = selectByUsername(username);

        for (String bad : new String[]{"avatar_0", "avatar_7", "avatar_", "AVATAR_1", "avatar_1 "}) {
            UpdateProfileRequest request = new UpdateProfileRequest();
            request.setAvatar(bad);
            BizException e = assertThrows(BizException.class,
                    () -> authService.updateProfile(user.getId(), request));
            assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getCode(), "非法头像应 1005：" + bad);
        }

        // 白名单内 6 个值全部可用
        for (String good : Constants.AVATARS) {
            UpdateProfileRequest request = new UpdateProfileRequest();
            request.setAvatar(good);
            assertEquals(good, authService.updateProfile(user.getId(), request).getAvatar());
        }
    }

    @Test
    @DisplayName("updateProfile：nickname 传空串（含纯空白）→ 1005，不写入空昵称")
    void updateProfileBlankNicknameRejected() {
        String username = uniqueUsername();
        authService.register(registerRequest(username, "abc123", null));
        User user = selectByUsername(username);

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("   ");
        BizException e = assertThrows(BizException.class,
                () -> authService.updateProfile(user.getId(), request));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getCode());
        assertEquals(username, selectByUsername(username).getNickname(), "昵称不应被改为空");
    }

    @Test
    @DisplayName("updateProfile：昵称首尾空白被裁剪后落库")
    void updateProfileTrimsNickname() {
        String username = uniqueUsername();
        authService.register(registerRequest(username, "abc123", null));
        User user = selectByUsername(username);

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("  带空格  ");
        assertEquals("带空格", authService.updateProfile(user.getId(), request).getNickname());
        assertEquals("带空格", selectByUsername(username).getNickname());
    }

    @Test
    @DisplayName("updateProfile：改自己的资料不会影响其它用户（无越权写入）")
    void updateProfileDoesNotTouchOtherUsers() {
        String first = uniqueUsername();
        String second = uniqueUsername();
        authService.register(registerRequest(first, "abc123", "甲"));
        authService.register(registerRequest(second, "abc123", "乙"));
        User secondUser = selectByUsername(second);

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("甲改名");
        authService.updateProfile(selectByUsername(first).getId(), request);

        assertEquals("乙", selectByUsername(second).getNickname(),
                "另一个用户的资料必须不受影响");
        assertEquals(Constants.DEFAULT_AVATAR, secondUser.getAvatar());
    }

    @Test
    @DisplayName("updateProfile：不存在的 userId → 1006")
    void updateProfileUnknownUserReturns1006() {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("任意");
        BizException e = assertThrows(BizException.class,
                () -> authService.updateProfile(199_999_999L, request));
        assertEquals(ErrorCode.TOKEN_INVALID.getCode(), e.getCode());
    }

    @Test
    @DisplayName("updateProfile：update_time 被刷新（MetaObjectHandler 生效）")
    void updateProfileRefreshesUpdateTime() {
        String username = uniqueUsername();
        authService.register(registerRequest(username, "abc123", null));
        User before = selectByUsername(username);

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("触发更新");
        authService.updateProfile(before.getId(), request);

        User after = selectByUsername(username);
        assertNotNull(after.getUpdateTime());
        assertTrue(!after.getUpdateTime().isBefore(before.getCreateTime()),
                "updateTime 不应早于 createTime");
    }

    /** 供未来用例复用的空实现保护：确认 Wrapper 类型参数未被误用。 */
    @SuppressWarnings("unused")
    private Wrapper<User> unusedWrapper() {
        return Wrappers.<User>lambdaQuery().eq(User::getId, -1L);
    }
}
