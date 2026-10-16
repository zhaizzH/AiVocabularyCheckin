package com.vocab.dto.response;

import java.time.LocalDateTime;

/**
 * AI 学习报告响应（《接口详细设计》§6.3）。
 *
 * <pre>
 * { "report": { "overview": "近两周共学习…", "weakness": "…", "suggestion": "…" },
 *   "generatedAt": "2026-10-16 15:30:00", "degraded": false }
 * </pre>
 *
 * <p>{@code generatedAt} 由注入的 {@code Clock} 取「当前时间」，
 * 序列化为契约 §0 要求的 {@code YYYY-MM-DD HH:mm:ss}（见 {@code JacksonConfig}）。
 * 报告失败（5001/5002）时本对象不会被构造。
 */
public class AiReportResponse {

    private AiReportContent report;
    private LocalDateTime generatedAt;
    private boolean degraded;

    public AiReportResponse() {
    }

    public AiReportResponse(AiReportContent report, LocalDateTime generatedAt, boolean degraded) {
        this.report = report;
        this.generatedAt = generatedAt;
        this.degraded = degraded;
    }

    public AiReportContent getReport() {
        return report;
    }

    public void setReport(AiReportContent report) {
        this.report = report;
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(LocalDateTime generatedAt) {
        this.generatedAt = generatedAt;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }
}
