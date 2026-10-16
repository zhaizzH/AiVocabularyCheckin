package com.vocab.dto.response;

import java.time.LocalDateTime;

/**
 * 错题列表元素（《接口详细设计》§5.1）。
 *
 * <pre>
 * { "mistakeId": 501, "wordId": 101, "word": "abandon", "phonetic": "…",
 *   "translation": "v. 放弃", "source": "STUDY", "wrongCount": 3,
 *   "mastered": false, "createTime": "2026-10-14 21:03:11" }
 * </pre>
 *
 * <p>{@code source} 取<b>最近一次</b>出错来源（STUDY / REVIEW）——
 * {@code mistake_entry.source} 单列只存一个值，重复出错时会被覆盖，
 * 语义是「最近来源」而非「首次来源」（design.md §4.4）。
 *
 * <p>{@code createTime} 用契约 §0 规定的 {@code YYYY-MM-DD HH:mm:ss} 格式
 * （由 {@code JacksonConfig} 全局配置，非自带格式）。
 */
public class MistakeResponse {

    private Long mistakeId;
    private Long wordId;
    private String word;
    private String phonetic;
    private String translation;
    private String source;
    private Integer wrongCount;
    private Boolean mastered;
    private LocalDateTime createTime;

    public MistakeResponse() {
    }

    public Long getMistakeId() {
        return mistakeId;
    }

    public void setMistakeId(Long mistakeId) {
        this.mistakeId = mistakeId;
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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Integer getWrongCount() {
        return wrongCount;
    }

    public void setWrongCount(Integer wrongCount) {
        this.wrongCount = wrongCount;
    }

    public Boolean getMastered() {
        return mastered;
    }

    public void setMastered(Boolean mastered) {
        this.mastered = mastered;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
