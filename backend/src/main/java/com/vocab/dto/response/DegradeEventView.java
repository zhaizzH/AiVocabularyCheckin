package com.vocab.dto.response;

import java.time.LocalDateTime;

/**
 * 一条降级事件（《接口详细设计》§8.10 {@code recentEvents[]} 元素）。
 *
 * <pre>
 * { "time": "2026-10-16 14:32:05", "feature": "explain", "costMs": 30100,
 *   "reason": "TIMEOUT", "result": "抛 5001" }
 * </pre>
 *
 * <p><b>为什么单独定义本类而不是复用 {@code AiMetrics.DegradeEvent}</b>：
 * 后者是内存计数器的内部值对象，字段类型是 {@code LocalDateTime} 与原始类型，
 * 直接序列化虽可行，但监控组件的内部结构一旦变化就会连带改变对外契约。
 * DTO 在 Service 边界做一次显式映射，把「内部结构」与「对外契约」解耦
 * （这也是「Controller 不返回实体」的同一原则，只是对象从 entity 换成
 * 内部值对象）。
 *
 * <p>{@code reason} 取值 {@code TIMEOUT} / {@code ERROR} / {@code CIRCUIT_OPEN}；
 * {@code result} 是对用户可见的结果描述（如「返回库内例句」/「抛 5001」），
 * 直接写进降级日志与监控表格。
 */
public class DegradeEventView {

    /** 降级发生时间，由 JacksonConfig 输出为 {@code yyyy-MM-dd HH:mm:ss}。 */
    private LocalDateTime time;

    /** example / explain / report。 */
    private String feature;

    /** 该次调用实际耗时（毫秒）。 */
    private Long costMs;

    /** TIMEOUT / ERROR / CIRCUIT_OPEN。 */
    private String reason;

    /** 用户可见的降级结果描述。 */
    private String result;

    public DegradeEventView() {
    }

    public DegradeEventView(LocalDateTime time, String feature, Long costMs,
                            String reason, String result) {
        this.time = time;
        this.feature = feature;
        this.costMs = costMs;
        this.reason = reason;
        this.result = result;
    }

    public LocalDateTime getTime() {
        return time;
    }

    public void setTime(LocalDateTime time) {
        this.time = time;
    }

    public String getFeature() {
        return feature;
    }

    public void setFeature(String feature) {
        this.feature = feature;
    }

    public Long getCostMs() {
        return costMs;
    }

    public void setCostMs(Long costMs) {
        this.costMs = costMs;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }
}
