package com.vocab.service;

import com.vocab.common.Constants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 间隔复习调度算法单测（design.md §4.3）。
 *
 * <p>纯函数、毫秒级、无 Spring 上下文。之所以单独为这三个小方法写测试：
 * 它们是「阶段 ↔ 间隔天数」的唯一真相源，一旦 {@code +1 封顶 4} 或
 * 间隔表的索引算法被改错，学习/复习两个模块会同时错，而顺序执行的功能
 * 测试未必立刻发现（错误只在特定 stage 上暴露，例如只有 stage=4 才谈得上「封顶」）。
 */
class ReviewScheduleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    @Test
    @DisplayName("间隔表与需求清单 §3.2 的 1/3/7/15 天逐位一致")
    void intervalsMatchRequirement() {
        assertArrayEquals(new int[] {1, 3, 7, 15}, Constants.REVIEW_INTERVALS);
    }

    @Test
    @DisplayName("区间天数：stage 1..4 → 1/3/7/15 天")
    void intervalDaysByStage() {
        assertEquals(1, ReviewSchedule.intervalDays(1));
        assertEquals(3, ReviewSchedule.intervalDays(2));
        assertEquals(7, ReviewSchedule.intervalDays(3));
        assertEquals(15, ReviewSchedule.intervalDays(4));
    }

    @Test
    @DisplayName("答对：stage+1，且在 4 处封顶（不越界到 5）")
    void nextStageIncrementsAndCaps() {
        assertEquals(2, ReviewSchedule.nextStage(1));
        assertEquals(3, ReviewSchedule.nextStage(2));
        assertEquals(4, ReviewSchedule.nextStage(3));
        // 封顶：已是上限时保持不变，而不是变成 5
        assertEquals(4, ReviewSchedule.nextStage(4));
    }

    @Test
    @DisplayName("越界的 stage 输入被夹到 [1,4]（容错，不抛异常）")
    void nextStageClampsOutOfRangeInput() {
        // stage=0 是 word_progress 的默认值（未进入复习），视为第 1 阶段
        assertEquals(2, ReviewSchedule.nextStage(0));
        assertEquals(2, ReviewSchedule.nextStage(-5));
        assertEquals(4, ReviewSchedule.nextStage(99));
    }

    @Test
    @DisplayName("越界的 stage 在 intervalDays 上同样被夹到 [1,4]")
    void intervalDaysClampsOutOfRangeInput() {
        assertEquals(1, ReviewSchedule.intervalDays(0));
        assertEquals(1, ReviewSchedule.intervalDays(-1));
        assertEquals(15, ReviewSchedule.intervalDays(5));
        assertEquals(15, ReviewSchedule.intervalDays(100));
    }

    @Test
    @DisplayName("下次复习日期按新 stage 的间隔计算（含跨月/跨年）")
    void nextReviewDateByStage() {
        assertEquals(TODAY.plusDays(1), ReviewSchedule.nextReviewDate(1, TODAY));
        assertEquals(TODAY.plusDays(3), ReviewSchedule.nextReviewDate(2, TODAY));
        assertEquals(TODAY.plusDays(7), ReviewSchedule.nextReviewDate(3, TODAY));
        assertEquals(TODAY.plusDays(15), ReviewSchedule.nextReviewDate(4, TODAY));
    }

    @Test
    @DisplayName("跨月边界：10-31 答对到 stage=2 → 11-03")
    void nextReviewDateCrossesMonthBoundary() {
        LocalDate endOfMonth = LocalDate.of(2026, 10, 31);
        assertEquals(LocalDate.of(2026, 11, 3), ReviewSchedule.nextReviewDate(2, endOfMonth));
    }

    @Test
    @DisplayName("端到端序列：从 stage=1 起连续答对 4 次 → 2/3/4/4，日期依次 +3/+7/+15/+15")
    void fullCorrectProgression() {
        int stage = Constants.MIN_STAGE;
        LocalDate date = TODAY;

        stage = ReviewSchedule.nextStage(stage);
        assertEquals(2, stage);
        date = ReviewSchedule.nextReviewDate(stage, date);
        assertEquals(TODAY.plusDays(3), date);

        stage = ReviewSchedule.nextStage(stage);
        assertEquals(3, stage);
        date = ReviewSchedule.nextReviewDate(stage, date);
        assertEquals(TODAY.plusDays(10), date);

        stage = ReviewSchedule.nextStage(stage);
        assertEquals(4, stage);
        date = ReviewSchedule.nextReviewDate(stage, date);
        assertEquals(TODAY.plusDays(25), date);

        // 封顶后间隔稳定在 15 天
        stage = ReviewSchedule.nextStage(stage);
        assertEquals(4, stage);
        date = ReviewSchedule.nextReviewDate(stage, date);
        assertEquals(TODAY.plusDays(40), date);
    }
}
