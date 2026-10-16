package com.vocab.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * AI 错题讲解请求（《接口详细设计》§6.2）。
 *
 * <p>只传 {@code mistakeId}：单词、释义、来源、错误次数全部由后端从
 * {@code mistake_entry} + {@code word} 取出。前端不传文本。
 */
public class AiExplainRequest {

    @NotNull(message = "错题 id 不能为空")
    private Long mistakeId;

    public AiExplainRequest() {
    }

    public Long getMistakeId() {
        return mistakeId;
    }

    public void setMistakeId(Long mistakeId) {
        this.mistakeId = mistakeId;
    }
}
