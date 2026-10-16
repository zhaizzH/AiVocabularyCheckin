package com.vocab.ai.dto;

import java.util.List;

/**
 * 业务后端 → ai-service 的学习报告请求（《接口详细设计》§9.3）。
 *
 * <pre>
 * POST /ai/v1/report  { "studyStats": { "studyDays": 12, "totalLearned": 346,
 *   "checkInRate": 85, "topMistakeWords": ["abandon","…"], "masteryRate": 58 } }
 * </pre>
 *
 * <p>统计数字全部来自本服务的 SQL 聚合（{@code StatsMapper}），
 * ai-service 只负责把这些数字写成自然语言，<b>不接触数据库</b>
 * （架构红线：ai-service 禁连 MySQL，见 spec/directory-structure.md）。
 */
public class AiReportCallRequest {

    private StudyStats studyStats;

    public AiReportCallRequest() {
    }

    public AiReportCallRequest(StudyStats studyStats) {
        this.studyStats = studyStats;
    }

    public StudyStats getStudyStats() {
        return studyStats;
    }

    public void setStudyStats(StudyStats studyStats) {
        this.studyStats = studyStats;
    }

    /** 传给 AI 的学习统计数据（契约 §9.3 字段一一对应）。 */
    public static class StudyStats {

        /** 累计学习天数（有打卡记录的天数）。 */
        private int studyDays;

        /** 累计已学单词数。 */
        private int totalLearned;

        /** 打卡率（百分比整数，0–100）。 */
        private int checkInRate;

        /** 错题最多的前若干单词。 */
        private List<String> topMistakeWords;

        /** 掌握度（百分比整数，0–100）。 */
        private int masteryRate;

        public StudyStats() {
        }

        public StudyStats(int studyDays, int totalLearned, int checkInRate,
                          List<String> topMistakeWords, int masteryRate) {
            this.studyDays = studyDays;
            this.totalLearned = totalLearned;
            this.checkInRate = checkInRate;
            this.topMistakeWords = topMistakeWords;
            this.masteryRate = masteryRate;
        }

        public int getStudyDays() {
            return studyDays;
        }

        public void setStudyDays(int studyDays) {
            this.studyDays = studyDays;
        }

        public int getTotalLearned() {
            return totalLearned;
        }

        public void setTotalLearned(int totalLearned) {
            this.totalLearned = totalLearned;
        }

        public int getCheckInRate() {
            return checkInRate;
        }

        public void setCheckInRate(int checkInRate) {
            this.checkInRate = checkInRate;
        }

        public List<String> getTopMistakeWords() {
            return topMistakeWords;
        }

        public void setTopMistakeWords(List<String> topMistakeWords) {
            this.topMistakeWords = topMistakeWords;
        }

        public int getMasteryRate() {
            return masteryRate;
        }

        public void setMasteryRate(int masteryRate) {
            this.masteryRate = masteryRate;
        }
    }
}
