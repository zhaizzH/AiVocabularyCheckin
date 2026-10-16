package com.vocab.common;

/**
 * 掌握度百分比计算（唯一真相源）。
 *
 * <p><b>为什么单独抽一个类</b>：掌握度公式同时被两处使用——
 * 统计模块的掌握度曲线（契约 §7.2）与 AI 学习报告的入参
 * （契约 §9.3 的 {@code studyStats.masteryRate}）。
 * 两处各写一遍「已掌握 / 总数 × 100」迟早会漂移（一处改取整方式、
 * 一处忘了处理分母为 0），而前端会看到同一个用户在两个页面显示
 * 不同的掌握度。故把公式收在唯一一处。
 *
 * <p><b>分母为 0 时取 100 而非 0</b>：语义是「没有未掌握的错题」。
 * 取 0 会被解读成「一个都没掌握」，与事实相反（一个都没错，全部是会的）。
 * 这条口径已记入 research/08 §7，待 G6 回写契约。
 */
public final class MasteryRates {

    private MasteryRates() {
    }

    /**
     * 计算掌握度百分比（整数，四舍五入）。
     *
     * @param masteredCount 已掌握错题数
     * @param totalCount    错题总数（含已掌握）
     * @return 0..100 的整数；{@code totalCount <= 0} 时返回 100
     */
    public static int percent(int masteredCount, int totalCount) {
        if (totalCount <= 0) {
            return 100;
        }
        return (int) Math.round(masteredCount * 100.0 / totalCount);
    }
}
