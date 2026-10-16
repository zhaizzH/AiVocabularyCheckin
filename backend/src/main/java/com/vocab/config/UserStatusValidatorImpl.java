package com.vocab.config;

import com.vocab.common.Constants;
import com.vocab.entity.User;
import com.vocab.mapper.UserMapper;
import org.springframework.stereotype.Component;

/**
 * {@link UserStatusValidator} 的数据库实现。
 *
 * <p>契约 §8.7「禁用后该用户 JWT 下次校验即失败」——因此每个受保护请求
 * 都要拿一次真实的 {@code user.status}，而不是信任 token 载荷。
 *
 * <p><b>为什么每请求查库</b>（design.md §5.2）：一期用户表规模小、QPS 低，
 * 一次主键查询（毫秒级）完全可以接受；换来的是「禁用即时生效」这条
 * 演示关键路径的确定性。若将来 QPS 上来，可在本类内部加短 TTL 缓存
 * （如 10s），调用方无需改动。
 */
@Component
public class UserStatusValidatorImpl implements UserStatusValidator {

    private final UserMapper userMapper;

    public UserStatusValidatorImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public AccountState check(Long userId) {
        if (userId == null) {
            return AccountState.UNKNOWN;
        }
        // 主键查询，且 @TableLogic 会自动附加 deleted = 0：
        // 已逻辑删除的账号在这里等价于「不存在」。
        User user = userMapper.selectById(userId);
        if (user == null) {
            return AccountState.UNKNOWN;
        }
        return isEnabled(user) ? AccountState.ACTIVE : AccountState.DISABLED;
    }

    private boolean isEnabled(User user) {
        return user.getStatus() != null && user.getStatus() == Constants.STATUS_ENABLED;
    }
}
