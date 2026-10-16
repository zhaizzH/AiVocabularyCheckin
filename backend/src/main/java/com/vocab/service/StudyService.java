package com.vocab.service;

import com.vocab.dto.response.CheckInResponse;
import com.vocab.dto.response.StreakResponse;
import com.vocab.dto.response.StudyPlanResponse;
import com.vocab.dto.response.StudyRecordResponse;

/**
 * 学习服务：今日计划 / 逐词上报 / 打卡 / 连续天数（契约 §3）。
 *
 * <p><b>本接口是核心链路，不依赖任何 AI 能力</b>（需求清单 §4.3 与
 * prd.md 的硬约束：AI 服务不可用时学习/打卡/复习必须照常工作）。
 */
public interface StudyService {

    /** 今日学习计划（契约 §3.1）。未选词书时返回 {@code total=0, words=[]}。 */
    StudyPlanResponse plan(Long userId);

    /**
     * 逐词学习上报（契约 §3.2）。{@code known=false} 时立即收录错题本。
     *
     * @throws com.vocab.common.BizException 1005 wordId/bookId 非法或不属于同一词书
     */
    StudyRecordResponse record(Long userId, Long wordId, Long bookId, boolean known);

    /**
     * 打卡（契约 §3.3）。
     *
     * @throws com.vocab.common.BizException 3001 今日已打卡；3002 今日无学习计划；
     *                                       3003 学习计划未完成
     */
    CheckInResponse checkIn(Long userId);

    /**
     * 连续打卡与累计统计（契约 §3.4）。
     *
     * <p>{@code streakDays} 返回<b>当前仍在延续</b>的连续天数：
     * 今天已打卡返回今日值；昨天打过、今天还没打仍返回那个值；
     * 最后一次打卡早于昨天则已中断，返回 0。
     */
    StreakResponse streak(Long userId);
}
