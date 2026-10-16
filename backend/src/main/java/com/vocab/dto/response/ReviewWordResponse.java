package com.vocab.dto.response;

/**
 * 今日待复习列表元素（《接口详细设计》§4.1）。
 *
 * <pre>
 * { "wordId": 101, "word": "tremendous", "phonetic": "trəˈmendəs",
 *   "translation": "adj. 巨大的", "example": "…", "stage": 2, "wrongCount": 1 }
 * </pre>
 *
 * <p>{@code stage} = 当前间隔阶段（1–4，对应 1/3/7/15 天间隔）；
 * {@code wrongCount} 取 {@code mistake_entry.wrong_count}（展示口径的权威值），
 * 该词若从未进错题本则为 0。
 */
public class ReviewWordResponse {

    private Long wordId;
    private String word;
    private String phonetic;
    private String translation;
    private String example;
    private Integer stage;
    private Integer wrongCount;

    public ReviewWordResponse() {
    }

    public Long getWordId() {
        return wordId;
    }

    public void setWordId(Long wordId) {
        this.wordId = wordId;
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

    public Integer getStage() {
        return stage;
    }

    public void setStage(Integer stage) {
        this.stage = stage;
    }

    public Integer getWrongCount() {
        return wrongCount;
    }

    public void setWrongCount(Integer wrongCount) {
        this.wrongCount = wrongCount;
    }
}
