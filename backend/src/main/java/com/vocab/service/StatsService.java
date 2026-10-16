package com.vocab.service;

import com.vocab.dto.response.HeatmapResponse;
import com.vocab.dto.response.MasteryResponse;

/**
 * 统计服务（契约 §7）。
 *
 * <p>职责边界：<b>SQL 只做按天聚合，Java 只做补零与分档</b>——
 * 「哪些天有数据」由 SQL 回答，「完整日期序列」由 Service 补全。
 * 这样既满足「聚合在 SQL 内完成、不加载原始记录到内存」的红线
 * （需求清单 §5.1），又让「连续日期序列」这条展示规则不污染 SQL。
 */
public interface StatsService {

    /**
     * 打卡热力图（契约 §7.1）。
     *
     * @param userId 用户 id
     * @param weeks  回溯周数（{@code null} 或非正数取默认 12）
     * @return 连续日期序列，长度 = {@code weeks × 7}，最后一天为今天
     */
    HeatmapResponse heatmap(Long userId, Integer weeks);

    /**
     * 掌握度曲线（契约 §7.2）。
     *
     * @param userId 用户 id
     * @param days   回溯天数（{@code null} 或非正数取默认 30）
     * @return 连续日期序列，长度 = {@code days}，最后一天为今天
     */
    MasteryResponse mastery(Long userId, Integer days);
}
