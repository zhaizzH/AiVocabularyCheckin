package com.vocab.ai.dto;

/**
 * ai-service 错题讲解响应（《接口详细设计》§9.2）。
 *
 * <pre>
 * { "explanation": "…", "advice": "…" }
 * </pre>
 */
public class AiExplainCallResponse {

    private String explanation;
    private String advice;

    public AiExplainCallResponse() {
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
}
