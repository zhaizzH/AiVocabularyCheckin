package com.vocab.dto.response;

/**
 * 单词元素（《接口详细设计》§2.2 词书详情 words.list 元素 / §8.2 管理端单词分页元素）。
 *
 * <pre>
 * { "wordId": 101, "word": "abandon", "phonetic": "əˈbændən",
 *   "translation": "v. 放弃；抛弃", "example": "He decided to abandon the plan." }
 * </pre>
 *
 * <p><b>为什么不含 {@code bookId} / {@code definition} / {@code audioUrl}</b>：
 * 契约 §2.2 与 §8.2 的元素字段清单只列了上面 5 个（`wordId` 必有）。
 * {@code definition} 一期留空、{@code audio_url} 一期不填（需求清单 §7.4/§7.5），
 * 传出去只会是恒 null 的噪声字段。若后续契约补充再一并加入。
 */
public class WordResponse {

    private Long wordId;
    private String word;
    private String phonetic;
    private String translation;
    private String example;

    public WordResponse() {
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
}
