package com.vocab.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 单词学习进度表 {@code word_progress} 实体（新增第 7 张表，见 design.md §3.2）。
 *
 * <p><b>为什么需要本表</b>：S3 §7 只定义 6 张表，但契约 §4 的 {@code stage}
 * 与 {@code nextReviewDate} 无处存放。{@code study_record} 是逐次事件流水，
 * 把「当前进度状态」塞进去会产生同词多行、字段互相覆盖的脏语义。
 * 故新增本表承载「每用户每单词的当前进度状态」，这是对 S3 的显式记录偏离。
 *
 * <p><b>本表存的是进度状态，不是「今日待复习列表」的快照</b>——
 * 待复习列表仍由 {@code next_review_date <= CURDATE() AND learned = 1} 实时计算，
 * 与 S3 §5.4「复习列表为计算生成，不设独立存储表」并不冲突。
 *
 * <p>唯一索引 {@code uk_word_progress_user_word (user_id, word_id)} 是设计关键：
 * 学习上报天然幂等（同词只一行，重复上报走 UPDATE）。
 */
@TableName("word_progress")
public class WordProgress {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 词书 id（冗余，便于按词书筛「未学单词」而不必联查 word 表）。 */
    private Long bookId;

    private Long wordId;

    /**
     * 间隔阶段：0 = 未进入复习，1..4 对应间隔 1/3/7/15 天。
     *
     * <p>{@code stage} 直接决定下次复习间隔（design.md §4.3）。
     */
    private Integer stage;

    /** 下次复习日期；NULL = 不参与复习。索引 idx_word_progress_user_review 后导列。 */
    private LocalDate nextReviewDate;

    /** 1 = 已学过（进入过学习计划并上报）。 */
    private Integer learned;

    /**
     * 累计错误次数——<b>仅用于复习调度参考</b>。
     * 对外展示的权威值是 {@code mistake_entry.wrong_count}，写入时两者同步更新。
     */
    private Integer wrongCount;

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

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public Long getWordId() {
        return wordId;
    }

    public void setWordId(Long wordId) {
        this.wordId = wordId;
    }

    public Integer getStage() {
        return stage;
    }

    public void setStage(Integer stage) {
        this.stage = stage;
    }

    public LocalDate getNextReviewDate() {
        return nextReviewDate;
    }

    public void setNextReviewDate(LocalDate nextReviewDate) {
        this.nextReviewDate = nextReviewDate;
    }

    public Integer getLearned() {
        return learned;
    }

    public void setLearned(Integer learned) {
        this.learned = learned;
    }

    public Integer getWrongCount() {
        return wrongCount;
    }

    public void setWrongCount(Integer wrongCount) {
        this.wrongCount = wrongCount;
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
