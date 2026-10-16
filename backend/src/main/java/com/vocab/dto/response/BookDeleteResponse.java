package com.vocab.dto.response;

/**
 * 删除词书响应（《接口详细设计》§8.9）。
 *
 * <pre>
 * { "bookId": 5, "deleted": true }
 * </pre>
 *
 * <p>与 {@link WordDeleteResponse} 同款结构但 bookId 语义不同，故独立成类：
 * 两者若共用一个泛型「{@code DeletedResponse{id}}」，字段名就会从
 * {@code bookId} / {@code wordId} 退化成 {@code id}，与契约不符（前端按名取值）。
 */
public class BookDeleteResponse {

    private Long bookId;
    private Boolean deleted;

    public BookDeleteResponse() {
    }

    public BookDeleteResponse(Long bookId, Boolean deleted) {
        this.bookId = bookId;
        this.deleted = deleted;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}
