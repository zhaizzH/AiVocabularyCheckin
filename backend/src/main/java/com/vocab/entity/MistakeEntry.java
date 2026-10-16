package com.vocab.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 错题表 {@code mistake_entry} 实体（S3 §7.2 表 7.6）。
 *
 * <p>收录去重规则（需求清单 §3.1 + design.md §4.4）：
 * 同一 {@code (user_id, word_id)} 重复出错 → {@code wrong_count + 1}，
 * <b>不重复建记录</b>；已掌握（{@code mastered=1}）的词再次答错 → 重置为 0。
 *
 * <p>本表<b>刻意不加</b> {@code (user_id, word_id)} 唯一索引：逻辑删除的行仍留在
 * 表中，「移除后重新收录」会与历史行冲突，故去重交应用层保证（design.md §4.4）。
 */
@TableName("mistake_entry")
public class MistakeEntry {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long wordId;

    /** 来源：STUDY 学习中「不认识」/ REVIEW 复习答错。 */
    private String source;

    /** 累计错误次数。<b>权威值</b>——契约 §5.1 直接返回本列。 */
    private Integer wrongCount;

    /** 0 未掌握 / 1 已掌握。 */
    private Integer mastered;

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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getWordId() {
        return wordId;
    }

    public void setWordId(Long wordId) {
        this.wordId = wordId;
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

    public Integer getMastered() {
        return mastered;
    }

    public void setMastered(Integer mastered) {
        this.mastered = mastered;
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
