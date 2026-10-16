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
 * 学习记录表 {@code study_record} 实体（S3 §7.2 表 7.5）。
 *
 * <p>语义：<b>逐次事件流水</b>——每次「学习/复习」上报写一条。
 * {@code check_in_date} 仅在打卡当日写入（未打卡的行该列为 NULL），
 * 因此<b>热力图按 {@code check_in_date} 聚合</b>（S3 §7.5 + 需求清单 §5.1）。
 *
 * <p>{@code streak_days} 是打卡当时的连续天数<b>快照</b>，
 * 便于历史回溯（连续天数由 {@code StudyService} 计算后写入）。
 *
 * <p>唯一索引 {@code uk_study_record_user_word_date_type}
 * (user_id, word_id, study_date, study_type) 防止并发重复打卡产生重复行
 * （契约未定义，属刻意加固，见 design.md §4.2）。
 */
@TableName("study_record")
public class StudyRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long bookId;

    private Long wordId;

    /** STUDY 学习 / REVIEW 复习。 */
    private String studyType;

    /** 学习日期。索引 idx_user_date 后导列。 */
    private LocalDate studyDate;

    /** 打卡日期；仅打卡当日写入，其它情况为 NULL。热力图按本列聚合。 */
    private LocalDate checkInDate;

    /** 当日连续打卡天数快照。 */
    private Integer streakDays;

    /** 0 答错 / 1 答对（仅复习时使用，学习时为 NULL）。 */
    private Integer correct;

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

    public String getStudyType() {
        return studyType;
    }

    public void setStudyType(String studyType) {
        this.studyType = studyType;
    }

    public LocalDate getStudyDate() {
        return studyDate;
    }

    public void setStudyDate(LocalDate studyDate) {
        this.studyDate = studyDate;
    }

    public LocalDate getCheckInDate() {
        return checkInDate;
    }

    public void setCheckInDate(LocalDate checkInDate) {
        this.checkInDate = checkInDate;
    }

    public Integer getStreakDays() {
        return streakDays;
    }

    public void setStreakDays(Integer streakDays) {
        this.streakDays = streakDays;
    }

    public Integer getCorrect() {
        return correct;
    }

    public void setCorrect(Integer correct) {
        this.correct = correct;
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
