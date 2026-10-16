package com.vocab.dto.response;

/**
 * 单个 AI 功能的调用统计（《接口详细设计》§8.10 {@code features[]} 元素）。
 *
 * <pre>
 * { "feature": "example", "total": 120, "success": 118, "degraded": 2,
 *   "timeout": 1, "error": 1, "avgCostMs": 1520 }
 * </pre>
 *
 * <p><b>数据来源是内存计数器</b>（{@code com.vocab.ai.AiMetrics}），因此
 * 所有计数都是「服务启动以来」的累计值，重启即清零（需求清单 §6.3
 * 「一期可用内存计数器实现」）。本类是 DTO，不是计数器本身——把
 * {@code AtomicLong} 直接序列化出去会暴露内部字段名（如 {@code totalCostMs}）。
 *
 * <p><b>为什么三个功能即使零调用也要出现</b>：管理端监控页是固定三行表格
 * （admin-prototype.html：例句 / 讲解 / 报告）。若只返回有调用的功能，
 * 前端就要自己补空行，且「某功能一次都没被调用」这个信息会被隐藏成
 * 缺行。{@code AiMetrics.snapshot()} 因此固定按 example/explain/report 顺序返回。
 *
 * <p>{@code total} <b>含降级</b>（否则成功率虚高）；{@code avgCostMs}
 * 同样含降级调用（耗时是真实发生的成本）。
 */
public class AiFeatureStat {

    /** example / explain / report。 */
    private String feature;

    /** 该功能调用总次数（含降级）。 */
    private Integer total;

    /** 成功次数。 */
    private Integer success;

    /** 降级次数（= timeout + error）。 */
    private Integer degraded;

    /** 超时导致的降级次数（契约 §6 语义 → 5001）。 */
    private Integer timeout;

    /** 其它异常导致的降级次数（契约 §6 语义 → 5002）。 */
    private Integer error;

    /** 平均耗时（毫秒，含降级）；无调用为 0。 */
    private Long avgCostMs;

    public AiFeatureStat() {
    }

    public String getFeature() {
        return feature;
    }

    public void setFeature(String feature) {
        this.feature = feature;
    }

    public Integer getTotal() {
        return total;
    }

    public void setTotal(Integer total) {
        this.total = total;
    }

    public Integer getSuccess() {
        return success;
    }

    public void setSuccess(Integer success) {
        this.success = success;
    }

    public Integer getDegraded() {
        return degraded;
    }

    public void setDegraded(Integer degraded) {
        this.degraded = degraded;
    }

    public Integer getTimeout() {
        return timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }

    public Integer getError() {
        return error;
    }

    public void setError(Integer error) {
        this.error = error;
    }

    public Long getAvgCostMs() {
        return avgCostMs;
    }

    public void setAvgCostMs(Long avgCostMs) {
        this.avgCostMs = avgCostMs;
    }
}
