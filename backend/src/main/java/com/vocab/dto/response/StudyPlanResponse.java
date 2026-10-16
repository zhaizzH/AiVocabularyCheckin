package com.vocab.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * 今日学习计划（《接口详细设计》§3.1）。
 *
 * <pre>
 * { "date": "2026-10-16", "total": 20, "finished": 14, "checkedIn": false, "words": [ … ] }
 * </pre>
 *
 * <p>{@code total} = {@code words} 的长度（复习词 + 每本已选词书最多 20 个新词）；
 * {@code finished} = 计划里今天已上报过的词数（见 {@link StudyWordResponse 关于 learned 的说明}）。
 *
 * <p>未选任何词书时返回 {@code total=0, finished=0, words=[]}，
 * 前端据此渲染「先去选一本词书」空态（需求清单 §2.2 边界）。
 */
public class StudyPlanResponse {

    private LocalDate date;
    private Integer total;
    private Integer finished;
    private Boolean checkedIn;
    private List<StudyWordResponse> words;

    public StudyPlanResponse() {
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Integer getTotal() {
        return total;
    }

    public void setTotal(Integer total) {
        this.total = total;
    }

    public Integer getFinished() {
        return finished;
    }

    public void setFinished(Integer finished) {
        this.finished = finished;
    }

    public Boolean getCheckedIn() {
        return checkedIn;
    }

    public void setCheckedIn(Boolean checkedIn) {
        this.checkedIn = checkedIn;
    }

    public List<StudyWordResponse> getWords() {
        return words;
    }

    public void setWords(List<StudyWordResponse> words) {
        this.words = words;
    }
}
