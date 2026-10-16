package com.vocab.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * 提交复习结果（《接口详细设计》§4.2）。
 *
 * <pre>
 * { "wordId": 101, "correct": true }
 * </pre>
 *
 * <p><b>刻意不带 {@code bookId}</b>：与学习上报不同，复习进度由
 * {@code uk_word_progress_user_word (user_id, word_id)} 定位，
 * 词书维度可从已有的 {@code word_progress.book_id} 读出。
 * 契约 §4.2 也只定义了 wordId 与 correct 两个字段。
 */
public class ReviewRecordRequest {

    @NotNull(message = "缺少参数：wordId")
    private Long wordId;

    /** true = 答对（进入下一阶段）；false = 答错（退回第 1 阶段）。 */
    @NotNull(message = "缺少参数：correct")
    private Boolean correct;

    public Long getWordId() {
        return wordId;
    }

    public void setWordId(Long wordId) {
        this.wordId = wordId;
    }

    public Boolean getCorrect() {
        return correct;
    }

    public void setCorrect(Boolean correct) {
        this.correct = correct;
    }
}
