package com.vocab.dto.response;

/**
 * AI 学习报告的三段落正文（《接口详细设计》§6.3）。
 *
 * <pre>
 * { "overview": "近两周共学习…", "weakness": "错题集中在…", "suggestion": "建议…" }
 * </pre>
 */
public class AiReportContent {

    private String overview;
    private String weakness;
    private String suggestion;

    public AiReportContent() {
    }

    public AiReportContent(String overview, String weakness, String suggestion) {
        this.overview = overview;
        this.weakness = weakness;
        this.suggestion = suggestion;
    }

    public String getOverview() {
        return overview;
    }

    public void setOverview(String overview) {
        this.overview = overview;
    }

    public String getWeakness() {
        return weakness;
    }

    public void setWeakness(String weakness) {
        this.weakness = weakness;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }
}
