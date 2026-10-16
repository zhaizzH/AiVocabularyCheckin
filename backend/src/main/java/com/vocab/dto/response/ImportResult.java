package com.vocab.dto.response;

/**
 * 词库导入结果（《接口详细设计》§8.1）。
 *
 * <pre>
 * { "bookId": 4, "success": 7500, "skipped": 8 }
 * </pre>
 *
 * <p>种子导入（{@code VocabSeedRunner}）与管理端上传
 * （{@code POST /api/admin/books/import}）共用本结构——
 * 两者走同一套解析逻辑（design.md §7）。
 */
public class ImportResult {

    private Long bookId;

    /** 成功写入的单词行数。 */
    private int success;

    /** 跳过（格式错误 / 缺必填 / 超列宽）的行数。 */
    private int skipped;

    public ImportResult() {
    }

    public ImportResult(Long bookId, int success, int skipped) {
        this.bookId = bookId;
        this.success = success;
        this.skipped = skipped;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public int getSuccess() {
        return success;
    }

    public void setSuccess(int success) {
        this.success = success;
    }

    public int getSkipped() {
        return skipped;
    }

    public void setSkipped(int skipped) {
        this.skipped = skipped;
    }
}
