package com.vocab.dto.response;

import java.time.LocalDate;

/**
 * 连续打卡统计（《接口详细设计》§3.4）。
 *
 * <pre>
 * { "streakDays": 12, "totalLearned": 346, "todayReviewCount": 8,
 *   "lastCheckInDate": "2026-10-15" }
 * </pre>
 *
 * <p><b>{@code streakDays} 是「当前仍在延续的连续天数」</b>，
 * 不是「最后一次打卡时的快照」：
 * <ul>
 *   <li>今天已打卡 → 今天的 streak 值</li>
 *   <li>昨天打过卡、今天还没打 → 昨天那个值（连续尚未中断，明天打卡会 +1）</li>
 *   <li>最后一次打卡早于昨天 → 已中断，返回 0</li>
 * </ul>
 * 若直接返回「最后一条 {@code streak_days} 快照」，断签一个月后首页仍显示 12 天，
 * 与实际状态不符。此口径已回写契约（§3.4 边界说明）。
 */
public class StreakResponse {

    private Integer streakDays;
    private Integer totalLearned;
    private Integer todayReviewCount;
    private LocalDate lastCheckInDate;

    public StreakResponse() {
    }

    public StreakResponse(Integer streakDays, Integer totalLearned,
                          Integer todayReviewCount, LocalDate lastCheckInDate) {
        this.streakDays = streakDays;
        this.totalLearned = totalLearned;
        this.todayReviewCount = todayReviewCount;
        this.lastCheckInDate = lastCheckInDate;
    }

    public Integer getStreakDays() {
        return streakDays;
    }

    public void setStreakDays(Integer streakDays) {
        this.streakDays = streakDays;
    }

    public Integer getTotalLearned() {
        return totalLearned;
    }

    public void setTotalLearned(Integer totalLearned) {
        this.totalLearned = totalLearned;
    }

    public Integer getTodayReviewCount() {
        return todayReviewCount;
    }

    public void setTodayReviewCount(Integer todayReviewCount) {
        this.todayReviewCount = todayReviewCount;
    }

    public LocalDate getLastCheckInDate() {
        return lastCheckInDate;
    }

    public void setLastCheckInDate(LocalDate lastCheckInDate) {
        this.lastCheckInDate = lastCheckInDate;
    }
}
