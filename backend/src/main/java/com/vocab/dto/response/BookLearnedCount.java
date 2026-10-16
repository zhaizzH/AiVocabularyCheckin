package com.vocab.dto.response;

/**
 * 词书已学词数聚合投影（内部使用，不作为接口响应直接返回）。
 *
 * <p>用途：避免 {@code GET /api/client/books} 对每本词书各查一次
 * {@code COUNT(*)}（N+1）。{@code StatsMapper.selectLearnedCountByBook}
 * 一次 {@code GROUP BY book_id} 返回「每本词书已学词数」，
 * Service 侧转成 {@code bookId → count} 的 Map 后再填充 {@link BookResponse#getLearnedCount()}。
 *
 * <p>本类是 MyBatis 映射目标，故保留无参构造 + setter（不用 record）。
 */
public class BookLearnedCount {

    private Long bookId;
    private Integer learnedCount;

    public BookLearnedCount() {
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public Integer getLearnedCount() {
        return learnedCount;
    }

    public void setLearnedCount(Integer learnedCount) {
        this.learnedCount = learnedCount;
    }
}
