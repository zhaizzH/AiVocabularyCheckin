package com.vocab.dto.response;

import java.util.List;

/**
 * 打卡热力图（《接口详细设计》§7.1）。
 *
 * <pre>
 * { "days": [ { "date": "2026-10-16", "wordCount": 20, "level": 2 } ] }
 * </pre>
 *
 * <p><b>{@code days} 恒为连续日期序列（不跳日、不空档）</b>：
 * 契约 §7.1 的日历格是「近 12 周 × 7 天」的固定网格，前端按格子位置渲染，
 * 若后端只返回「有打卡的日期」，前端就得自己补零并重新推算每格对应哪一天——
 * 补零是后端的职责（阶段 9 闸口明确要求 84 个点）。
 * 因此本响应中的 {@code days} 长度 = {@code weeks × 7}，
 * 且相邻元素日期严格相差 1 天、最后一个元素为「今天」。
 *
 * <p><b>空态如何判定</b>：无学习数据时本响应仍返回完整日期序列，
 * 但所有点的 {@code level} 均为 0——前端据此整块替换为「还没有学习数据」引导态
 * （原型 {@code miniprogram-states.html} 的 F9）。
 */
public class HeatmapResponse {

    private List<HeatmapDay> days;

    public HeatmapResponse() {
    }

    public HeatmapResponse(List<HeatmapDay> days) {
        this.days = days;
    }

    public List<HeatmapDay> getDays() {
        return days;
    }

    public void setDays(List<HeatmapDay> days) {
        this.days = days;
    }
}
