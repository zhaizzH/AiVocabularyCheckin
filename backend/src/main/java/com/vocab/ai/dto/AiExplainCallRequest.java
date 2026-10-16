package com.vocab.ai.dto;

/**
 * 业务后端 → ai-service 的错题讲解请求（《接口详细设计》§9.2）。
 *
 * <pre>
 * POST /ai/v1/explain  { "word": "abandon", "translation": "…", "wrongContext": "用户误选「举办」" }
 * </pre>
 */
public class AiExplainCallRequest {

    private String word;
    private String translation;

    /**
     * 用户答错的上下文。
     *
     * <p>契约 §9.2 示例为 {@code "用户误选「举办」"}——但本项目的错题只记录
     * {@code word_id} 与错误次数，<b>不记录用户当时选了什么</b>（没有选项数据）。
     * 因此这里传入由已有数据构造的替代上下文（来源 + 错误次数），
     * 让 AI 至少能围绕「这个词反复出错」给出讲解。这是实现层面的合理折中，
     * 已在 design.md 与本类注明。
     */
    private String wrongContext;

    public AiExplainCallRequest() {
    }

    public AiExplainCallRequest(String word, String translation, String wrongContext) {
        this.word = word;
        this.translation = translation;
        this.wrongContext = wrongContext;
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

    public String getWrongContext() {
        return wrongContext;
    }

    public void setWrongContext(String wrongContext) {
        this.wrongContext = wrongContext;
    }
}
