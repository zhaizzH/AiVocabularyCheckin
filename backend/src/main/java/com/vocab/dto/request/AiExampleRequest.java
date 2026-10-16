package com.vocab.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * AI 例句请求（《接口详细设计》§6.1）。
 *
 * <p>请求体只有 {@code wordId}——<b>单词文本由后端从库中取出</b>再传给 AI，
 * 前端不传文本（契约 §6 开头明示）。这样有三个好处：
 * <ol>
 *   <li>AI 拿到的释义必然与库里一致，不会被前端篡改；</li>
 *   <li>一次调用只需传一个 id，请求体极小；</li>
 *   <li>改词库不需改前端。</li>
 * </ol>
 */
public class AiExampleRequest {

    @NotNull(message = "单词 id 不能为空")
    private Long wordId;

    public AiExampleRequest() {
    }

    public Long getWordId() {
        return wordId;
    }

    public void setWordId(Long wordId) {
        this.wordId = wordId;
    }
}
