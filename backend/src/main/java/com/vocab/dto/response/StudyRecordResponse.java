package com.vocab.dto.response;

/**
 * 学习结果上报响应（《接口详细设计》§3.2）。
 *
 * <pre>
 * { "wordId": 101, "learned": true, "addedToMistakes": true }
 * </pre>
 *
 * <p>{@code addedToMistakes} 仅在 {@code known=false} 时为 {@code true}；
 * 已存在错题记录时表示「已累加 {@code wrong_count} 并重置 {@code mastered}」，
 * 不是「新建了一条错题」。
 */
public class StudyRecordResponse {

    private Long wordId;
    private Boolean learned;
    private Boolean addedToMistakes;

    public StudyRecordResponse() {
    }

    public StudyRecordResponse(Long wordId, Boolean learned, Boolean addedToMistakes) {
        this.wordId = wordId;
        this.learned = learned;
        this.addedToMistakes = addedToMistakes;
    }

    public Long getWordId() {
        return wordId;
    }

    public void setWordId(Long wordId) {
        this.wordId = wordId;
    }

    public Boolean getLearned() {
        return learned;
    }

    public void setLearned(Boolean learned) {
        this.learned = learned;
    }

    public Boolean getAddedToMistakes() {
        return addedToMistakes;
    }

    public void setAddedToMistakes(Boolean addedToMistakes) {
        this.addedToMistakes = addedToMistakes;
    }
}
