package com.vocab.ai;

/**
 * ai-service 调用失败的内部异常（不直接暴露给前端）。
 *
 * <p>与 {@link com.vocab.common.BizException} 的区别：本异常只表达
 * 「调用 AI 这件事本身失败了」，<b>由调用方决定如何降级</b>——
 * 例句要吞掉它返回兜底内容，讲解/报告要把它翻译成 5001/5002。
 * 若让底层直接抛 5001/5002，例句那句「降级不能抛异常」的契约
 * 就会在下游被破坏（异常一抛就没有回头路）。
 */
public class AiCallException extends RuntimeException {

    /** 是否由超时引起（true → 映射 5001；false → 映射 5002）。 */
    private final boolean timeout;

    public AiCallException(boolean timeout, String message, Throwable cause) {
        super(message, cause);
        this.timeout = timeout;
    }

    public boolean isTimeout() {
        return timeout;
    }
}
