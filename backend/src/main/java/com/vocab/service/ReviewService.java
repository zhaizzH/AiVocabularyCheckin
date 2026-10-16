package com.vocab.service;

import com.vocab.dto.response.ReviewRecordResponse;
import com.vocab.dto.response.ReviewTodayResponse;

/**
 * 复习服务（契约 §4 + 需求清单 §3.2）。
 *
 * <p>调度规则见 {@link ReviewSchedule}（间隔 1/3/7/15 天，答对 +1 封顶 4、
 * 答错退回 1）。本接口只负责「取待复习列表」与「落库一次复习结果」。
 */
public interface ReviewService {

    /**
     * 今日待复习（契约 §4.1）。
     *
     * <p>定义：{@code word_progress} 中 {@code learned = 1} 且
     * {@code next_review_date <= 今天} 的词，按到期日升序（最早到期先复习）。
     *
     * <p><b>刻意不接受分页参数</b>：契约 §4.1 的响应是 {@code {total, words}}，
     * 且需求清单 §3.2 明确该列表「计算生成、不建表存储」——
     * 其规模由「今天到期」决定，不存在无上限增长。
     */
    ReviewTodayResponse today(Long userId);

    /**
     * 提交复习结果（契约 §4.2）。
     *
     * <p>correct=true → {@code stage + 1}（封顶 4）；correct=false →
     * {@code stage = 1}、{@code wrong_count + 1}、确保收录错题本。
     * 两者都会写一条 {@code study_record}（{@code study_type = REVIEW}）
     * 并按新 stage 计算 {@code next_review_date}。
     *
     * @throws com.vocab.common.BizException 1005 wordId 非法（该词无学习进度）
     */
    ReviewRecordResponse record(Long userId, Long wordId, boolean correct);
}
