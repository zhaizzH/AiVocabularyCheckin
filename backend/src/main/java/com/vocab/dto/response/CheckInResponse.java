package com.vocab.dto.response;

import java.time.LocalDate;

/**
 * 打卡响应（《接口详细设计》§3.3）。
 *
 * <pre>
 * { "checkInDate": "2026-10-16", "streakDays": 12, "todayLearned": 20, "todayMistakes": 3 }
 * </pre>
 *
 * <p>{@code todayLearned} = 今日学习（STUDY）去重词数；
 * {@code todayMistakes} = 今日新收录的错题条目数。
 */
public class CheckInResponse {

    private LocalDate checkInDate;
    private Integer streakDays;
    private Integer todayLearned;
    private Integer todayMistakes;

    public CheckInResponse() {
    }

    public CheckInResponse(LocalDate checkInDate, Integer streakDays,
                           Integer todayLearned, Integer todayMistakes) {
        this.checkInDate = checkInDate;
        this.streakDays = streakDays;
        this.todayLearned = todayLearned;
        this.todayMistakes = todayMistakes;
    }

    public LocalDate getCheckInDate() {
        return checkInDate;
    }

    public void setCheckInDate(LocalDate checkInDate) {
        this.checkInDate = checkInDate;
    }

    public Integer getStreakDays() {
        return streakDays;
    }

    public void setStreakDays(Integer streakDays) {
        this.streakDays = streakDays;
    }

    public Integer getTodayLearned() {
        return todayLearned;
    }

    public void setTodayLearned(Integer todayLearned) {
        this.todayLearned = todayLearned;
    }

    public Integer getTodayMistakes() {
        return todayMistakes;
    }

    public void setTodayMistakes(Integer todayMistakes) {
        this.todayMistakes = todayMistakes;
    }
}
