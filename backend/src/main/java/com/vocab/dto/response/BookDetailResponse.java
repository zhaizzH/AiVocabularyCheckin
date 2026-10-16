package com.vocab.dto.response;

import com.vocab.common.PageResult;

/**
 * 词书详情（《接口详细设计》§2.2）。
 *
 * <pre>
 * { "bookId": 1, "name": "四级核心词汇", "category": "CET4", "wordCount": 7508,
 *   "words": { "list": [ { "wordId": 101, "word": "abandon", … } ],
 *              "total": 7508, "page": 1, "size": 20 } }
 * </pre>
 *
 * <p>{@code words} 是标准分页结构（契约 §0），因此直接复用
 * {@link PageResult}，而不是再定义一个 {"list","total","page","size"} 的类——
 * 分页结构在全项目只有一个定义，避免 `PageResult` 与手写分页结构漂移。
 *
 * <p>额外带 {@code description}：与 §2.1 是同一资源，词书详情页需要展示简介；
 * 属契约 §2.2 字段清单的**无害超集**（只增不改，前端可忽略）。
 */
public class BookDetailResponse {

    private Long bookId;
    private String name;
    private String category;
    private Integer wordCount;
    private String description;
    private PageResult<WordResponse> words;

    public BookDetailResponse() {
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getWordCount() {
        return wordCount;
    }

    public void setWordCount(Integer wordCount) {
        this.wordCount = wordCount;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public PageResult<WordResponse> getWords() {
        return words;
    }

    public void setWords(PageResult<WordResponse> words) {
        this.words = words;
    }
}
