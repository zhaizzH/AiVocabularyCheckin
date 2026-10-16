package com.vocab.dto.response;

import java.util.List;

/**
 * 掌握度曲线（《接口详细设计》§7.2）。
 *
 * <pre>
 * { "points": [ { "date": "2026-10-16", "learnedCount": 20, "reviewCount": 8,
 *                 "mistakeCount": 3, "masteryRate": 58 } ] }
 * </pre>
 *
 * <p><b>{@code points} 恒为连续日期序列</b>：与 {@link HeatmapResponse} 同理，
 * 折线图需要每一天都有坐标点才能连成连续曲线；缺失的日期在此补零
 * （{@code learnedCount/reviewCount/mistakeCount = 0}），
 * 长度 = {@code days}、最后一个元素为「今天」。
 *
 * <p><b>{@code masteryRate} 是当日快照</b>而非当日增量：它等于
 * 「当前已掌握错题 / 当前错题总数 × 100」，同一天序列里各点的值会随
 * 错题掌握状态变化——这是刻意的，因为「掌握度」本质是一个
 * <b>存量指标</b>（progress），不是流水指标（flow）。契约只规定公式
 * 「已掌握错题 / 总错题 × 100」，未规定它随时间变化的口径，
 * 取快照比取「当日新增错题中已掌握的比例」更符合直觉，也更省一次逐日聚合。
 */
public class MasteryResponse {

    private List<MasteryPoint> points;

    public MasteryResponse() {
    }

    public MasteryResponse(List<MasteryPoint> points) {
        this.points = points;
    }

    public List<MasteryPoint> getPoints() {
        return points;
    }

    public void setPoints(List<MasteryPoint> points) {
        this.points = points;
    }
}
