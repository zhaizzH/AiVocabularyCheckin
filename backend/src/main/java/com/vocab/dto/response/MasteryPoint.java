package com.vocab.dto.response;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 掌握度曲线单日数据点（契约 §7.2）。
 *
 * <pre>
 * { "date": "2026-10-16", "learnedCount": 20, "reviewCount": 8,
 *   "mistakeCount": 3, "masteryRate": 58 }
 * </pre>
 *
 * <p>{@code masteryRate}（整数百分比）= 已掌握错题 / 总错题 × 100。
 * 该值为<b>当日快照</b>口径（见 {@code StatsService} 注释），
 * 由 Java 侧统一计算，不逐日在 SQL 里算。
 *
 * <p>本类为 MyBatis 映射目标，需保留无参构造与 setter。
 */
public class MasteryPoint implements Serializable {

    private LocalDate date;
    private Integer learnedCount;
    private Integer reviewCount;
    private Integer mistakeCount;
    private Integer masteryRate;

    public MasteryPoint() {
    }

    public MasteryPoint(LocalDate date, Integer learnedCount, Integer reviewCount,
                        Integer mistakeCount, Integer masteryRate) {
        this.date = date;
        this.learnedCount = learnedCount;
        this.reviewCount = reviewCount;
        this.mistakeCount = mistakeCount;
        this.masteryRate = masteryRate;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Integer getLearnedCount() {
        return learnedCount;
    }

    public void setLearnedCount(Integer learnedCount) {
        this.learnedCount = learnedCount;
    }

    public Integer getReviewCount() {
        return reviewCount;
    }

    public void setReviewCount(Integer reviewCount) {
        this.reviewCount = reviewCount;
    }

    public Integer getMistakeCount() {
        return mistakeCount;
    }

    public void setMistakeCount(Integer mistakeCount) {
        this.mistakeCount = mistakeCount;
    }

    public Integer getMasteryRate() {
        return masteryRate;
    }

    public void setMasteryRate(Integer masteryRate) {
        this.masteryRate = masteryRate;
    }
}
