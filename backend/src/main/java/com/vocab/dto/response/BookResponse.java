package com.vocab.dto.response;

/**
 * 词库列表元素（《接口详细设计》§2.1）。
 *
 * <pre>
 * { "bookId": 1, "name": "四级核心词汇", "category": "CET4", "wordCount": 7508,
 *   "description": "…", "selected": true, "learnedCount": 346 }
 * </pre>
 *
 * <p>{@code selected} = 当前用户是否已加入该词书；
 * {@code learnedCount} = 该用户在此词书已学词数（{@code word_progress.learned = 1}）。
 *
 * <p>这两个字段是**按用户**计算的，因此本 DTO 只能由 Service 在拿到 userId 后构造，
 * 不能直接把 {@code word_book} 实体序列化出去（那会漏掉用户维度的信息）。
 */
public class BookResponse {

    private Long bookId;
    private String name;
    private String category;
    private Integer wordCount;
    private String description;
    private Boolean selected;
    private Integer learnedCount;

    public BookResponse() {
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

    public Boolean getSelected() {
        return selected;
    }

    public void setSelected(Boolean selected) {
        this.selected = selected;
    }

    public Integer getLearnedCount() {
        return learnedCount;
    }

    public void setLearnedCount(Integer learnedCount) {
        this.learnedCount = learnedCount;
    }
}
