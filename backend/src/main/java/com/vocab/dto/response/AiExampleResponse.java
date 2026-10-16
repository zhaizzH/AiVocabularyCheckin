package com.vocab.dto.response;

/**
 * AI 例句响应（《接口详细设计》§6.1）。
 *
 * <pre>
 * { "wordId": 101, "example": "He decided to abandon the plan.",
 *   "exampleTranslation": "他决定放弃这个计划。",
 *   "mnemonic": "a(一个)+bandon(板凳)→连板凳都不要了→放弃", "degraded": false }
 * </pre>
 *
 * <p><b>{@code degraded} 是前端契约的关键字段</b>：AI 不可用时本接口
 * <b>不抛异常</b>，而是返回 {@code code:"0"} + {@code degraded:true} +
 * {@code example} 填库内例句、{@code mnemonic} 为空串。前端据此显示黄色提示条，
 * 而<b>不是</b>当成错误。详见 {@code AiServiceImpl} 中的显式注释。
 */
public class AiExampleResponse {

    private Long wordId;
    private String example;
    private String exampleTranslation;
    private String mnemonic;
    private boolean degraded;

    public AiExampleResponse() {
    }

    public AiExampleResponse(Long wordId, String example, String exampleTranslation,
                             String mnemonic, boolean degraded) {
        this.wordId = wordId;
        this.example = example;
        this.exampleTranslation = exampleTranslation;
        this.mnemonic = mnemonic;
        this.degraded = degraded;
    }

    public Long getWordId() {
        return wordId;
    }

    public void setWordId(Long wordId) {
        this.wordId = wordId;
    }

    public String getExample() {
        return example;
    }

    public void setExample(String example) {
        this.example = example;
    }

    public String getExampleTranslation() {
        return exampleTranslation;
    }

    public void setExampleTranslation(String exampleTranslation) {
        this.exampleTranslation = exampleTranslation;
    }

    public String getMnemonic() {
        return mnemonic;
    }

    public void setMnemonic(String mnemonic) {
        this.mnemonic = mnemonic;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }
}
