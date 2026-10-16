package com.vocab.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * 学习结果上报（《接口详细设计》§3.2）。
 *
 * <pre>
 * { "wordId": 101, "bookId": 1, "known": false }
 * </pre>
 *
 * <p>{@code bookId} 由前端带上（它从 §3.1 的计划里拿得到），
 * 用于把词归入正确的词书维度；Service 侧仍会校验该词确实属于该词书，
 * 避免前端传错导致 {@code word_progress.book_id} 与 {@code word.book_id} 不一致。
 */
public class StudyRecordRequest {

    @NotNull(message = "缺少参数：wordId")
    private Long wordId;

    @NotNull(message = "缺少参数：bookId")
    private Long bookId;

    /** true = 认识；false = 不认识（同时立即收录错题本）。 */
    @NotNull(message = "缺少参数：known")
    private Boolean known;

    public Long getWordId() {
        return wordId;
    }

    public void setWordId(Long wordId) {
        this.wordId = wordId;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public Boolean getKnown() {
        return known;
    }

    public void setKnown(Boolean known) {
        this.known = known;
    }
}
