package com.vocab.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户已选词书中间表 {@code user_word_book}（S3 §7.2 表 7.4）。
 *
 * <p><b>本表是物理删除</b>（移出词书即 DELETE 本行），因此
 * <b>没有 {@code deleted} 字段，也没有 {@code @TableLogic}</b>——
 * 这是全项目唯一不使用逻辑删除的业务表。
 * 移出词书不影响历史学习记录（{@code study_record} / {@code word_progress} 保留）。
 */
@TableName("user_word_book")
public class UserWordBook {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户 id。索引 idx_user_id + 唯一索引 uk_user_word_book_user_book 前导列。 */
    private Long userId;

    /** 词书 id。索引 idx_book_id。 */
    private Long bookId;

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

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
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
