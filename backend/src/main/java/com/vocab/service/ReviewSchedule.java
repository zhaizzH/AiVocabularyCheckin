package com.vocab.service;

import com.vocab.common.Constants;

import java.time.LocalDate;

/**
 * 间隔复习调度算法（纯函数，无状态）。
 *
 * <p>规则来源（需求清单 §3.2，已定为常量）：间隔序列 1 → 3 → 7 → 15 天；
 * 答对进入下一阶段（封顶 4），答错退回第 1 阶段。
 *
 * <p><b>为什么单独提出来</b>：这三个方法是整个复习模块的唯一真相源。若分散在
 * {@code StudyServiceImpl} 与 {@code ReviewServiceImpl} 各自算一遍，两处的
 * 「+1 封顶 4」与「查间隔表」很容易漂移成两套语义。
 * 本类无依赖、可单状测试（直接矩阵验证 stage 1→4 的转移）。
 */
public final class ReviewSchedule {

    private ReviewSchedule() {
    }

    /**
     * 答对后的新阶段：{@code min(stage + 1, MAX_STAGE)}。
     *
     * @param stage 当前阶段（小于 1 的值按 1 处理，容错）
     */
    public static int nextStage(int stage) {
        int current = Math.max(stage, Constants.MIN_STAGE);
        return Math.min(current + 1, Constants.MAX_STAGE);
    }

    /**
     * 某阶段对应的复习间隔（天）。
     *
     * <p>{@code stage} 直接决定间隔：stage=1→1 天、2→3 天、3→7 天、4→15 天。
     */
    public static int intervalDays(int stage) {
        int index = Math.min(Math.max(stage, Constants.MIN_STAGE), Constants.MAX_STAGE) - 1;
        return Constants.REVIEW_INTERVALS[index];
    }

    /**
     * 按新阶段计算下次复习日期。
     */
    public static LocalDate nextReviewDate(int stage, LocalDate today) {
        return today.plusDays(intervalDays(stage));
    }
}
