package com.vocab.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 单词表 {@code word} 实体（S3 §7.2 表 7.3）。
 *
 * <p>字段来源：KyleBing/english-vocabulary 的 {@code full_line_jsonl/sentence}
 * 数据（映射规则见 design.md §7.1）：
 * {@code word→word}、{@code uk→phonetic}、
 * {@code translations} 拼「类型. 释义」→{@code translation}、
 * {@code sentences[0].sentence→example}。
 *
 * <p>{@code definition} / {@code audioUrl} 一期留空（源数据无英文释义；
 * 需求清单 §7.4 不做发音播放，字段保留）。
 */
@TableName("word")
public class Word {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属词书 id。索引 idx_book_id。 */
    private Long bookId;

    private String word;

    /** 音标（英式 uk，需求清单要求英式）。 */
    private String phonetic;

    /** 中文释义。多义项以「；」连接，如 {@code "n. 谋杀；v. 杀害"}。 */
    private String translation;

    /** 英文释义。一期留空。 */
    private String definition;

    /** 内置示例例句。AI 例句降级时的兜底内容（契约 §6.1）。 */
    private String example;

    /** 发音音频 URL。一期留空。 */
    private String audioUrl;

    @TableLogic
    private Integer deleted;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getDefinition() {
        return definition;
    }

    public void setDefinition(String definition) {
        this.definition = definition;
    }

    public String getExample() {
        return example;
    }

    public void setExample(String example) {
        this.example = example;
    }

    public String getAudioUrl() {
        return audioUrl;
    }

    public void setAudioUrl(String audioUrl) {
        this.audioUrl = audioUrl;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
