package com.vocab.ai.dto;

/**
 * 业务后端 → ai-service 的例句生成请求（《接口详细设计》§9.1）。
 *
 * <pre>
 * POST /ai/v1/example  { "word": "abandon", "translation": "v. 放弃", "context": "CET4" }
 * </pre>
 *
 * <p>这是<b>内部</b>接口契约（前端不可见）。{@code context} 取词书分类（CET4/CET6/KAOYAN），
 * 让 AI 按对应难度出例句。
 */
public class AiExampleCallRequest {

    private String word;
    private String translation;
    private String context;

    public AiExampleCallRequest() {
    }

    public AiExampleCallRequest(String word, String translation, String context) {
        this.word = word;
        this.translation = translation;
        this.context = context;
    }

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
    }

    public String getTranslation() {
        return translation;
    }

    public void setTranslation(String translation) {
        this.translation = translation;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }
}
