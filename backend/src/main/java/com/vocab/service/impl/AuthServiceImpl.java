package com.vocab.service.impl;

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
import com.vocab.dto.response.UserInfoResponse;
import com.vocab.entity.User;
import com.vocab.mapper.StatsMapper;
import com.vocab.mapper.UserMapper;
import com.vocab.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 认证服务实现（《接口详细设计》§1、《需求清单》§1）。
 *
 * <p><b>安全红线</b>（spec/logging-guidelines.md）：
 * <ul>
 *   <li>密码只以 BCrypt 哈希入库，明文绝不落库/落日志</li>
 *   <li>登录失败记 WARN，但日志里只有用户名，绝无密码</li>
 *   <li>token 绝不记入日志</li>
 * </ul>
 *
 * <p><b>并发正确性</b>：唯一性「先查后插」本身存在竞态窗口
 * （两个并发注册请求可能都通过检查）。这里不靠乐观锁，
 * 而是让数据库唯一索引兜底——捕获 {@code DuplicateKeyException} 后
 * 转成同一个 1001 业务错误。检查是为了给出友好提示，索引才是正确性保证。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    /** BCrypt 哈希强度。10 是 Spring Security 默认值，兼顾安全与登录耗时（约 50–100ms）。 */
    private static final int BCRYPT_STRENGTH = 10;

    /** 生成微信昵称随机后缀的字符集（小写字母 + 数字）。 */
    private static final char[] SUFFIX_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final UserMapper userMapper;
    private final StatsMapper statsMapper;
    private final JwtUtil jwtUtil;
    private final WxApiClient wxApiClient;

    /**
     * 只用 {@code spring-security-crypto} 的 BCrypt，<b>不引</b>
     * {@code spring-boot-starter-security}，避免默认安全过滤链干扰自定义 JwtInterceptor
     * （design.md §5.3）。
     */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    public AuthServiceImpl(UserMapper userMapper,
                           StatsMapper statsMapper,
                           JwtUtil jwtUtil,
                           WxApiClient wxApiClient) {
        this.userMapper = userMapper;
        this.statsMapper = statsMapper;
        this.jwtUtil = jwtUtil;
        this.wxApiClient = wxApiClient;
    }

    // ---------------------------------------------------------------
    // §1.1 注册
    // ---------------------------------------------------------------

    @Override
    public RegisterResponse register(RegisterRequest request) {
        String username = request.getUsername().trim();

        // 友好路径：先查一次给出明确提示。正确性由下面的唯一索引兜底。
        if (findByUsername(username) != null) {
            throw new BizException(ErrorCode.USERNAME_TAKEN);
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(StringUtils.hasText(request.getNickname())
                ? request.getNickname().trim()
                : username);
        user.setAvatar(Constants.DEFAULT_AVATAR);
        user.setRole(Constants.ROLE_USER);
        user.setStatus(Constants.STATUS_ENABLED);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册同一用户名：数据库唯一索引拦截，与上面的检查同一语义。
            log.debug("并发注册用户名冲突 username={}", username);
            throw new BizException(ErrorCode.USERNAME_TAKEN);
        }

        log.info("用户注册成功 userId={} username={}", user.getId(), username);
        return new RegisterResponse(user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }

    // ---------------------------------------------------------------
    // §1.2 账号密码登录
    // ---------------------------------------------------------------

    @Override
    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername().trim();
        User user = findByUsername(username);

        // 账号不存在与密码错误返回同一个错误码 1002：对外不区分二者，
        // 避免把「哪些用户名已注册」暴露给攻击者（用户枚举）。
        // 日志里同样只记用户名，绝不记密码。
        if (user == null || !StringUtils.hasText(user.getPasswordHash())
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("登录失败（用户名或密码错误） username={}", username);
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }

        // 密码正确但账号被禁用 → 1003（与 1002 区分：这里凭证是对的，
        // 提示用户「联系管理员」而不是「重新输入密码」）。
        if (!isEnabled(user)) {
            log.warn("登录失败（账号已禁用） userId={}", user.getId());
            throw new BizException(ErrorCode.USER_DISABLED);
        }

        log.info("登录成功 userId={} role={}", user.getId(), user.getRole());
        return buildLoginResponse(user, null);
    }

    // ---------------------------------------------------------------
    // §1.3 微信登录
    // ---------------------------------------------------------------

    @Override
    public LoginResponse wxLogin(WxLoginRequest request) {
        String openid = wxApiClient.code2Session(request.getCode());

        User existing = findByOpenid(openid);
        if (existing != null) {
            // 账号禁用即时生效同样适用于微信登录路径。
            if (!isEnabled(existing)) {
                log.warn("微信登录失败（账号已禁用） userId={}", existing.getId());
                throw new BizException(ErrorCode.USER_DISABLED);
            }
            log.info("微信登录成功 userId={}", existing.getId());
            return buildLoginResponse(existing, null);
        }

        User created = createWxUser(openid);
        log.info("微信首次登录，已自动建号 userId={}", created.getId());
        return buildLoginResponse(created, Boolean.TRUE);
    }

    /**
     * 微信首次登录自动建号。
     *
     * <p>并发场景：同一用户快速点两次「微信登录」（或小程序重试）会发出两个
     * 不同 code，但换回同一个 openid，两个请求可能同时走到这里。
     * 此时唯一索引 {@code uk_openid} 会让后到的插入失败——
     * 这不是错误，重查一次即可拿到刚建好的账号。
     */
    private User createWxUser(String openid) {
        User user = new User();
        // username 留 NULL：微信用户没有用户名，登录只走 openid。
        // MySQL 唯一索引允许多个 NULL，故不影响 uk_username。
        user.setUsername(null);
        // passwordHash 留 NULL：该账号没有密码，账号密码登录路径必然失败（1002）。
        user.setPasswordHash(null);
        user.setOpenid(openid);
        user.setNickname(Constants.WX_NICKNAME_PREFIX + randomSuffix());
        user.setAvatar(Constants.DEFAULT_AVATAR);
        user.setRole(Constants.ROLE_USER);
        user.setStatus(Constants.STATUS_ENABLED);

        try {
            userMapper.insert(user);
            return user;
        } catch (DuplicateKeyException e) {
            User concurrent = findByOpenid(openid);
            if (concurrent == null) {
                // 唯一索引报冲突却查不到记录，说明冲突来自其它唯一键或数据异常，
                // 不能静默吞掉，交给全局处理器记为系统错误。
                throw e;
            }
            log.info("并发微信建号，复用已存在账号 userId={}", concurrent.getId());
            return concurrent;
        }
    }

    // ---------------------------------------------------------------
    // §1.4 / §1.5 个人信息
    // ---------------------------------------------------------------

    @Override
    public ProfileResponse profile(Long userId) {
        return toProfile(requireUser(userId));
    }

    @Override
    public ProfileResponse updateProfile(Long userId, UpdateProfileRequest request) {
        boolean hasNickname = request.getNickname() != null;
        boolean hasAvatar = request.getAvatar() != null;
        if (!hasNickname && !hasAvatar) {
            // 「至少一项」是跨字段约束，DTO 层表达不了（见 UpdateProfileRequest 注释）。
            throw new BizException(ErrorCode.PARAM_INVALID);
        }

        User user = requireUser(userId);

        if (hasNickname) {
            String nickname = request.getNickname().trim();
            if (nickname.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "昵称不能为空");
            }
            // DTO 的 @Size 已挡长度；这里再判一次是因为 trim 后可能变短，
            // 也防止 Service 被其它入口（如管理端）复用时绕过校验。
            if (nickname.length() > Constants.NICKNAME_MAX_LENGTH) {
                throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "昵称最多 20 字");
            }
            user.setNickname(nickname);
        }

        if (hasAvatar) {
            // 白名单校验以 Constants.AVATARS 为准（唯一事实源），
            // 不只依赖 DTO 的正则——保证非 HTTP 入口调用时同样安全。
            if (!Constants.isValidAvatar(request.getAvatar())) {
                throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "头像仅限 avatar_1 ~ avatar_6");
            }
            user.setAvatar(request.getAvatar());
        }

        userMapper.updateById(user);
        log.info("用户资料已更新 userId={}", userId);
        return toProfile(user);
    }

    // ---------------------------------------------------------------
    // 私有辅助
    // ---------------------------------------------------------------

    private User findByUsername(String username) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username));
    }

    private User findByOpenid(String openid) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getOpenid, openid));
    }

    /** 取当前用户；不存在（如账号已被删除但 token 未过期）→ 1006，引导重新登录。 */
    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        return user;
    }

    private boolean isEnabled(User user) {
        return user.getStatus() != null && user.getStatus() == Constants.STATUS_ENABLED;
    }

    private LoginResponse buildLoginResponse(User user, Boolean isNewUser) {
        String token = jwtUtil.generateToken(user.getId(), user.getRole());
        UserInfoResponse info = new UserInfoResponse();
        info.setUserId(user.getId());
        info.setUsername(user.getUsername());
        info.setNickname(user.getNickname());
        info.setAvatar(user.getAvatar());
        info.setRole(user.getRole());
        info.setIsNewUser(isNewUser);
        return new LoginResponse(token, info);
    }

    private ProfileResponse toProfile(User user) {
        ProfileResponse response = new ProfileResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setAvatar(user.getAvatar());
        response.setRole(user.getRole());
        response.setCreateTime(user.getCreateTime());
        // 累计打卡天数：契约 §1.4 要求。按「不同 check_in_date 的天数」统计。
        Integer checkInDays = statsMapper.countCheckInDays(user.getId());
        response.setTotalCheckInDays(checkInDays == null ? 0 : checkInDays);
        return response;
    }

    private String randomSuffix() {
        StringBuilder suffix = new StringBuilder(Constants.WX_NICKNAME_SUFFIX_LENGTH);
        for (int i = 0; i < Constants.WX_NICKNAME_SUFFIX_LENGTH; i++) {
            suffix.append(SUFFIX_CHARS[ThreadLocalRandom.current().nextInt(SUFFIX_CHARS.length)]);
        }
        return suffix.toString();
    }
}
