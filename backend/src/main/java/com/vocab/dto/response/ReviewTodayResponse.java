package com.vocab.dto.response;

import java.util.List;

/**
 * 今日待复习（《接口详细设计》§4.1）。
 *
 * <pre>
 * { "total": 8, "words": [ … ] }
 * </pre>
 *
 * <p><b>刻意不带分页结构</b>：契约 §4.1 的响应是 {@code {total, words}}，
 * 而需求清单 §3.2 明确「今日待复习列表 = 下次复习日期 ≤ 今天 且未掌握的单词，
 * 计算生成、不建表存储」，其规模天然受「今日到期」约束
 * （间隔序列决定每天最多累积一天的到期量），不存在无上限列表。
 * 此处遵循字段级契约，不用 {@code PageResult} 包一层。
 */
public class ReviewTodayResponse {

    private Integer total;
    private List<ReviewWordResponse> words;

    public ReviewTodayResponse() {
    }

    public ReviewTodayResponse(Integer total, List<ReviewWordResponse> words) {
        this.total = total;
        this.words = words;
    }

    public Integer getTotal() {
        return total;
    }

    public void setTotal(Integer total) {
        this.total = total;
    }

    public List<ReviewWordResponse> getWords() {
        return words;
    }

    public void setWords(List<ReviewWordResponse> words) {
        this.words = words;
    }
}
