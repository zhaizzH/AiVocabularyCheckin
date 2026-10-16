package com.vocab.dto.response;

/**
 * AI 错题讲解响应（《接口详细设计》§6.2）。
 *
 * <pre>
 * { "mistakeId": 501, "explanation": "你误选为「举办」。abandon 强调彻底放弃…",
 *   "advice": "用「放弃计划」搭配造一个自己的句子。", "degraded": false }
 * </pre>
 *
 * <p>与例句不同，<b>讲解没有内置兜底内容</b>，故降级时本对象根本不会被构造——
 * Service 直接抛 5001/5002（契约 §6.2 明确）。
 */
public class AiExplainResponse {

    private Long mistakeId;
    private String explanation;
    private String advice;
    private boolean degraded;

    public AiExplainResponse() {
    }

    public AiExplainResponse(Long mistakeId, String explanation, String advice, boolean degraded) {
        this.mistakeId = mistakeId;
        this.explanation = explanation;
        this.advice = advice;
        this.degraded = degraded;
    }

    public Long getMistakeId() {
        return mistakeId;
    }

    public void setMistakeId(Long mistakeId) {
        this.mistakeId = mistakeId;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public String getAdvice() {
        return advice;
    }

    public void setAdvice(String advice) {
        this.advice = advice;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }
}
