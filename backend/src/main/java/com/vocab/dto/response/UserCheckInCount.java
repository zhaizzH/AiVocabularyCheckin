package com.vocab.dto.response;

/**
 * 用户累计打卡天数聚合投影（内部使用，管理端用户列表专用）。
 *
 * <p><b>为什么需要它</b>：管理端用户列表每页 20 行，每行都要显示
 * {@code totalCheckInDays}（契约 §8.6）。若在 Java 里逐行调用
 * {@code StatsMapper.countCheckInDays(userId)}，一页就是 20 次 COUNT——
 * 典型 N+1（database-guidelines.md「常见错误」明确禁止）。
 * 故先分页查出 20 个用户 id，再用一次 {@code GROUP BY user_id}
 * 聚合（{@code StatsMapper.countCheckInDaysByUsers}）拿到这批用户的打卡天数，
 * 在内存里填充。SQL 次数恒为 2，与页大小无关。
 *
 * <p>本类是 MyBatis 映射目标，故保留无参构造 + setter（不用 record）。
 * 只返回「有打卡记录」的用户；无记录的用户由 Service 侧
 * {@code getOrDefault(userId, 0)} 兜底为 0。
 */
public class UserCheckInCount {

    private Long userId;

    /** 不同 {@code check_in_date} 的天数。 */
    private Integer checkInDays;

    public UserCheckInCount() {
    }

    public UserCheckInCount(Long userId, Integer checkInDays) {
        this.userId = userId;
        this.checkInDays = checkInDays;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getCheckInDays() {
        return checkInDays;
    }

    public void setCheckInDays(Integer checkInDays) {
        this.checkInDays = checkInDays;
    }
}
