package com.vocab.dto.response;

import java.util.List;

/**
 * AI 调用监控响应（《接口详细设计》§8.10）。
 *
 * <pre>
 * { "totalCalls": 342, "successRate": 96.2, "degradedCount": 13, "registeredUsers": 58,
 *   "features": [ { "feature": "example", "total": 120, ... } ],
 *   "recentEvents": [ { "time": "…", "feature": "explain", ... } ] }
 * </pre>
 *
 * <p><b>数据来源</b>：AI 计数取自进程内 {@code AiMetrics}（重启清零），
 * {@code registeredUsers} 取自数据库实时 COUNT。二者语义不同，故本响应
 * 在文档中显式注明「各计数均为服务启动以来的累计值」。
 *
 * <p>{@code totalCalls} 为三个功能 total 之和，<b>含降级</b>；
 * {@code successRate} = 成功数 / totalCalls × 100，保留 1 位小数，
 * <b>无调用时为 100</b>（而不是 0——「还没调用过」不等于「全失败」）。
 * 该口径与 {@code MasteryRates} 的「分母为 0 取 100」一致。
 *
 * <p>{@code degradedCount} 单列出来是为了监控页顶部的红色数字卡片
 * （admin-prototype.html「降级次数 13」）；它等于各功能 {@code degraded} 之和，
 * 属冗余字段，但省去前端一次求和，且能防止前端各页面自行求和时口径不一。
 */
public class AiStatsResponse {

    /** 三个 AI 功能调用总次数（含降级）。 */
    private Integer totalCalls;

    /** 成功率百分数，保留 1 位小数；无调用时为 100。 */
    private Double successRate;

    /** 降级总次数。 */
    private Integer degradedCount;

    /** 注册用户数（未逻辑删除）。 */
    private Integer registeredUsers;

    /** 各功能统计，顺序固定为 example / explain / report。 */
    private List<AiFeatureStat> features;

    /** 降级事件，最新在前，最多 100 条。 */
    private List<DegradeEventView> recentEvents;

    public AiStatsResponse() {
    }

    public Integer getTotalCalls() {
        return totalCalls;
    }

    public void setTotalCalls(Integer totalCalls) {
        this.totalCalls = totalCalls;
    }

    public Double getSuccessRate() {
        return successRate;
    }

    public void setSuccessRate(Double successRate) {
        this.successRate = successRate;
    }

    public Integer getDegradedCount() {
        return degradedCount;
    }

    public void setDegradedCount(Integer degradedCount) {
        this.degradedCount = degradedCount;
    }

    public Integer getRegisteredUsers() {
        return registeredUsers;
    }

    public void setRegisteredUsers(Integer registeredUsers) {
        this.registeredUsers = registeredUsers;
    }

    public List<AiFeatureStat> getFeatures() {
        return features;
    }

    public void setFeatures(List<AiFeatureStat> features) {
        this.features = features;
    }

    public List<DegradeEventView> getRecentEvents() {
        return recentEvents;
    }

    public void setRecentEvents(List<DegradeEventView> recentEvents) {
        this.recentEvents = recentEvents;
    }
}
