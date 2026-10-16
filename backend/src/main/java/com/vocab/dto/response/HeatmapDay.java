package com.vocab.dto.response;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 热力图单日数据点（契约 §7.1）。
 *
 * <pre>
 * { "date": "2026-10-16", "wordCount": 20, "level": 2 }
 * </pre>
 *
 * <p>{@code level} 由后端分档（0 未打卡 / 1 = 1–10 词 / 2 = 11–20 词 / 3 = 21+ 词），
 * 在 {@code StatsService} 中计算，SQL 只负责聚合出 {@code wordCount}。
 *
 * <p>本类为 MyBatis 映射目标，需保留无参构造与 setter（不用 record：
 * MyBatis 的列名 → 属性映射依赖 setter）。
 */
public class HeatmapDay implements Serializable {

    private LocalDate date;
    private Integer wordCount;
    private Integer level;

    public HeatmapDay() {
    }

    public HeatmapDay(LocalDate date, Integer wordCount, Integer level) {
        this.date = date;
        this.wordCount = wordCount;
        this.level = level;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Integer getWordCount() {
        return wordCount;
    }

    public void setWordCount(Integer wordCount) {
        this.wordCount = wordCount;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }
}
