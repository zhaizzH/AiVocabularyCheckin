package com.vocab.ai.dto;

/**
 * ai-service 例句生成响应（《接口详细设计》§9.1）。
 *
 * <pre>
 * { "example": "…", "exampleTranslation": "…", "mnemonic": "…" }
 * </pre>
 *
 * <p>用无参构造 + setter 而非 record：Jackson 反序列化 + 防御性判空更直观，
 * 也与本项目其它响应 DTO 风格一致。
 */
public class AiExampleCallResponse {

    private String example;
    private String exampleTranslation;
    private String mnemonic;

    public AiExampleCallResponse() {
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
}
