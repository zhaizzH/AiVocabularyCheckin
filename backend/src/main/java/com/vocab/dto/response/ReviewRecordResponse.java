package com.vocab.dto.response;

import java.time.LocalDate;

/**
 * 提交复习结果（《接口详细设计》§4.2）。
 *
 * <pre>
 * { "wordId": 101, "stage": 3, "nextReviewDate": "2026-10-23" }
 * </pre>
 *
 * <p>{@code stage} 是<b>更新后</b>的阶段：答对 {@code stage+1}（封顶 4）；
 * 答错重置为 1。{@code nextReviewDate} 按新 stage 查
 * {@code REVIEW_INTERVALS}（1/3/7/15 天）计算。
 */
public class ReviewRecordResponse {

    private Long wordId;
    private Integer stage;
    private LocalDate nextReviewDate;

    public ReviewRecordResponse() {
    }

    public ReviewRecordResponse(Long wordId, Integer stage, LocalDate nextReviewDate) {
        this.wordId = wordId;
        this.stage = stage;
        this.nextReviewDate = nextReviewDate;
    }

    public Long getWordId() {
        return wordId;
    }

    public void setWordId(Long wordId) {
        this.wordId = wordId;
    }

    public Integer getStage() {
        return stage;
    }

    public void setStage(Integer stage) {
        this.stage = stage;
    }

    public LocalDate getNextReviewDate() {
        return nextReviewDate;
    }

    public void setNextReviewDate(LocalDate nextReviewDate) {
        this.nextReviewDate = nextReviewDate;
    }
}
