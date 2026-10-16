package com.vocab.dto.response;

/**
 * 选词书 / 移出词书的响应（《接口详细设计》§2.3 / §2.4）。
 *
 * <pre>
 * POST   /api/client/books/1/select → { "bookId": 1, "selected": true  }
 * DELETE /api/client/books/1/select → { "bookId": 1, "selected": false }
 * </pre>
 *
 * <p>两个接口返回同一结构、仅 {@code selected} 相反，因此共用一个 DTO，
 * 避免两个近乎相同的类各自漂移。
 */
public class BookSelectResponse {

    private Long bookId;
    private Boolean selected;

    public BookSelectResponse() {
    }

    public BookSelectResponse(Long bookId, Boolean selected) {
        this.bookId = bookId;
        this.selected = selected;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public Boolean getSelected() {
        return selected;
    }

    public void setSelected(Boolean selected) {
        this.selected = selected;
    }
}
