package com.vocab.ai.dto;

/**
 * ai-service 学习报告响应（《接口详细设计》§9.3）。
 *
 * <pre>
 * { "overview": "…", "weakness": "…", "suggestion": "…" }
 * </pre>
 */
public class AiReportCallResponse {

    private String overview;
    private String weakness;
    private String suggestion;

    public AiReportCallResponse() {
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
