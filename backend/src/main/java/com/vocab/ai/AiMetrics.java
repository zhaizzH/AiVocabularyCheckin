package com.vocab.ai;

import com.vocab.common.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 调用监控埋点（design.md §6.3）。
 *
 * <p><b>一期明确不持久化</b>（需求清单 §6.3「一期可用简单内存计数器实现」）：
 * 重启即清零。同时每次降级都以 {@code WARN} 记日志，作为可持久观测信号——
 * 内存计数器服务「实时看板」，日志服务「事后追查」。
 *
 * <p><b>线程安全</b>：Web 请求由多个 Tomcat 工作线程并发处理，故
 * 计数器用 {@link AtomicLong}、按功能分组的 Map 用 {@link ConcurrentHashMap}、
 * 环形队列用 {@link ConcurrentLinkedDeque}。切勿换成普通 {@code HashMap}——
 * 高并发下会丢计数甚至死循环。
 *
 * <p><b>内存边界</b>：降级事件队列容量固定（{@link Constants#DEGRADE_EVENT_CAPACITY}），
 * 超出即从队首淘汰。若不做上界，长时间运行的服务会把所有降级事件累积到内存里——
 * 这正是监控组件自己变成故障源的经典方式。
 */
@Component
public class AiMetrics {

    private static final Logger log = LoggerFactory.getLogger(AiMetrics.class);

    /** 功能名常量，与契约 §6 三个接口一一对应。 */
    public static final String FEATURE_EXAMPLE = "example";
    public static final String FEATURE_EXPLAIN = "explain";
    public static final String FEATURE_REPORT = "report";

    /** 降级原因。 */
    public static final String REASON_TIMEOUT = "TIMEOUT";
    public static final String REASON_ERROR = "ERROR";
    public static final String REASON_CIRCUIT = "CIRCUIT_OPEN";

    /** 每个功能的调用统计。 */
    public static final class CallStat {

        private final AtomicLong total = new AtomicLong();
        private final AtomicLong success = new AtomicLong();
        private final AtomicLong degraded = new AtomicLong();
        private final AtomicLong timeout = new AtomicLong();
        private final AtomicLong error = new AtomicLong();
        private final AtomicLong totalCostMs = new AtomicLong();

        /** 平均耗时（毫秒，取整）。无调用时返回 0。 */
        public long avgCostMs() {
            long t = total.get();
            return t == 0 ? 0 : totalCostMs.get() / t;
        }

        public long getTotal() {
            return total.get();
        }

        public long getSuccess() {
            return success.get();
        }

        public long getDegraded() {
            return degraded.get();
        }

        public long getTimeout() {
            return timeout.get();
        }

        public long getError() {
            return error.get();
        }

        public long getTotalCostMs() {
            return totalCostMs.get();
        }
    }

    /** 一条降级事件（供管理端事件列表展示）。 */
    public static final class DegradeEvent {

        private final LocalDateTime time;
        private final String feature;
        private final long costMs;
        private final String reason;
        private final String result;

        DegradeEvent(LocalDateTime time, String feature, long costMs, String reason, String result) {
            this.time = time;
            this.feature = feature;
            this.costMs = costMs;
            this.reason = reason;
            this.result = result;
        }

        public LocalDateTime getTime() {
            return time;
        }

        public String getFeature() {
            return feature;
        }

        public long getCostMs() {
            return costMs;
        }

        public String getReason() {
            return reason;
        }

        public String getResult() {
            return result;
        }
    }

    private final Map<String, CallStat> stats = new ConcurrentHashMap<>();

    /**
     * 降级事件环形队列。
     *
     * <p>用 {@link java.util.concurrent.ConcurrentLinkedDeque} 而非
     * {@code ArrayDeque}：后者的 offer/remove 不是线程安全的，
     * 而这里由多个请求线程写入。
     */
    private final Deque<DegradeEvent> recentEvents = new ConcurrentLinkedDeque<>();

    public AiMetrics() {
        stats.put(FEATURE_EXAMPLE, new CallStat());
        stats.put(FEATURE_EXPLAIN, new CallStat());
        stats.put(FEATURE_REPORT, new CallStat());
    }

    /** 取得某功能的计数器（不存在则创建，防御拼写错误）。 */
    public CallStat stat(String feature) {
        return stats.computeIfAbsent(feature, k -> new CallStat());
    }

    /**
     * 记录一次<b>成功</b>调用。
     *
     * @param costMs 实际耗时（毫秒）
     */
    public void recordSuccess(String feature, long costMs) {
        CallStat stat = stat(feature);
        stat.total.incrementAndGet();
        stat.success.incrementAndGet();
        stat.totalCostMs.addAndGet(Math.max(costMs, 0));
    }

    /**
     * 记录一次<b>降级</b>调用并追加降级事件。
     *
     * <p>降级也计入 {@code total}——否则「成功率」会看起来很高，
     * 因为失败的调用被悄悄排除在分母外。
     *
     * @param reason {@link #REASON_TIMEOUT} / {@link #REASON_ERROR} / {@link #REASON_CIRCUIT}
     * @param result 对用户可见的结果描述（如「返回库内例句」/「抛 5002」）
     */
    public void recordDegrade(String feature, long costMs, String reason, String result,
                              LocalDateTime now) {
        CallStat stat = stat(feature);
        stat.total.incrementAndGet();
        stat.degraded.incrementAndGet();
        if (REASON_TIMEOUT.equals(reason)) {
            stat.timeout.incrementAndGet();
        } else {
            stat.error.incrementAndGet();
        }
        stat.totalCostMs.addAndGet(Math.max(costMs, 0));

        recentEvents.addFirst(new DegradeEvent(now, feature, costMs, reason, result));
        // 容量上界：超出即淘汰最旧的一条。
        while (recentEvents.size() > Constants.DEGRADE_EVENT_CAPACITY) {
            recentEvents.pollLast();
        }

        // WARN 日志：可持久观测信号（内存计数器重启即清零，日志不会）。
        log.warn("AI 降级 feature={} reason={} costMs={} result={}", feature, reason, costMs, result);
    }

    /** 降级事件快照（最新在前，最多 {@link Constants#DEGRADE_EVENT_CAPACITY} 条）。 */
    public List<DegradeEvent> recentEvents() {
        return new ArrayList<>(recentEvents);
    }

    /** 各功能统计快照，插入顺序固定为 example/explain/report（便于前端表格）。 */
    public Map<String, CallStat> snapshot() {
        Map<String, CallStat> ordered = new LinkedHashMap<>();
        ordered.put(FEATURE_EXAMPLE, stat(FEATURE_EXAMPLE));
        ordered.put(FEATURE_EXPLAIN, stat(FEATURE_EXPLAIN));
        ordered.put(FEATURE_REPORT, stat(FEATURE_REPORT));
        return ordered;
    }
}
