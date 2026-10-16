package com.vocab.dto.response;

import java.time.LocalDateTime;

/**
 * 管理端词书列表元素（《接口详细设计》§8.8）。
 *
 * <pre>
 * { "bookId": 1, "name": "四级核心词汇", "category": "CET4", "wordCount": 7508,
 *   "description": "…", "createTime": "2026-10-20 09:30:00" }
 * </pre>
 *
 * <p><b>为什么不复用 {@link BookResponse}</b>：契约 §8.8 明确「管理端无用户维度，
 * 故不含 {@code selected} / {@code learnedCount}，改为带 {@code createTime}」。
 * 管理端界面表格列是「名称 / 分类 / 单词数 / <b>创建时间</b> / 操作」
 * （admin-prototype.html 词库管理页），需要 {@code createTime}；
 * 而 {@code selected} / {@code learnedCount} 是学生端按用户计算的字段，
 * 在管理端恒为 {@code false} / {@code 0}，是纯噪声。
 *
 * <p>若强行复用 {@link BookResponse}，要么带上两个恒定的噪声字段、
 * 要么给它加一个只在管理端有意义的 {@code createTime}——两者都是把
 * 「学生端视图」与「管理端视图」两个不同契约耦合进一个类，日后任一端
 * 加字段都会污染另一端。故按契约分成两个 DTO。
 */
public class AdminBookResponse {

    private Long bookId;
    private String name;
    private String category;
    private Integer wordCount;
    private String description;

    /** 词书创建时间，由 JacksonConfig 输出为 {@code yyyy-MM-dd HH:mm:ss}。 */
    private LocalDateTime createTime;

    public AdminBookResponse() {
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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
