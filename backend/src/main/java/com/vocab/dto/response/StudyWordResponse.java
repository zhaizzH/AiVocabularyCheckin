package com.vocab.dto.response;

/**
 * 今日学习计划中的单个单词（《接口详细设计》§3.1）。
 *
 * <pre>
 * { "wordId": 101, "bookId": 1, "word": "abandon", "phonetic": "əˈbændən",
 *   "translation": "v. 放弃；抛弃", "example": "…",
 *   "isReview": false, "learned": false }
 * </pre>
 *
 * <p><b>{@code learned} 是「今日是否已上报」而非「历史上是否学过」</b>：
 * 契约 §3.1 说「{@code learned=true} 的词前端跳过不展示」，
 * §3.2 上报后的响应也是 {@code learned: true}——两处语义一致，
 * 指的都是「今天这个词已经处理过了」。若把它理解成
 * {@code word_progress.learned}（历史是否学过），则所有复习词
 * （{@code isReview=true}，定义上必然是已学过的）都会被前端跳过，
 * 与 §3.1「复习词排在新词前」直接冲突。此语义已回写契约（§3.1 边界说明）。
 *
 * <p>{@code isReview} = 该词今天到期待复习（来自 {@code word_progress} 的调度）。
 */
public class StudyWordResponse {

    private Long wordId;
    private Long bookId;
    private String word;
    private String phonetic;
    private String translation;
    private String example;
    private Boolean isReview;
    private Boolean learned;

    public StudyWordResponse() {
    }

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

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
    }

    public String getPhonetic() {
        return phonetic;
    }

    public void setPhonetic(String phonetic) {
        this.phonetic = phonetic;
    }

    public String getTranslation() {
        return translation;
    }

    public void setTranslation(String translation) {
        this.translation = translation;
    }

    public String getExample() {
        return example;
    }

    public void setExample(String example) {
        this.example = example;
    }

    public Boolean getIsReview() {
        return isReview;
    }

    public void setIsReview(Boolean isReview) {
        this.isReview = isReview;
    }

    public Boolean getLearned() {
        return learned;
    }

    public void setLearned(Boolean learned) {
        this.learned = learned;
    }
}
