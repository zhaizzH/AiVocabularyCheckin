package com.vocab.dto.response;

/**
 * 删除单词响应（《接口详细设计》§8.5）。
 *
 * <pre>
 * { "wordId": 101, "deleted": true }
 * </pre>
 *
 * <p>逻辑删除：只置 {@code word.deleted = 1}（{@code @TableLogic}），
 * 不物理 DELETE。用户可能仍有该词的 {@code word_progress} / {@code study_record}
 * 历史，物理删除会让历史记录指向悬空的 {@code word_id}。
 *
 * <p>{@code deleted:true} 不是「是否删除成功」的布尔结果（失败会以错误码返回，
 * 不会走到这里），而是对「已删除」这一状态的确认——与
 * {@code BookSelectResponse.selected} 的表达方式一致。
 */
public class WordDeleteResponse {

    private Long wordId;
    private Boolean deleted;

    public WordDeleteResponse() {
    }

    public WordDeleteResponse(Long wordId, Boolean deleted) {
        this.wordId = wordId;
        this.deleted = deleted;
    }

    public Long getWordId() {
        return wordId;
    }

    public void setWordId(Long wordId) {
        this.wordId = wordId;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}
