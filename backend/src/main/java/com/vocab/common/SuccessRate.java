package com.vocab.common;

/**
 * 成功率计算（百分数，保留 1 位小数）。
 *
 * <p>与 {@link MasteryRates} 同属「纯函数口径类」：一处定义、被 AI 监控与
 * 其它统计复用，避免「管理端看板算一个数、AI 报告算另一个数」的经典分叉。
 *
 * <p><b>两条口径必须写死在代码里，否则极易被改错</b>：
 * <ol>
 *   <li><b>分母含降级</b>（{@code total} 已包含失败调用）。若用「成功数 / 成功数」
 *       或把降级排除在分母外，成功率会永远 100%——一个永远 100% 的健康指标
 *       比没有指标更危险。</li>
 *   <li><b>无调用时返回 100 而非 0</b>：语义是「没有失败」。
 *       这与 {@link MasteryRates#percent} 的「分母为 0 取 100」保持同一约定，
 *       否则管理端新部署时看板会显示 0% 成功率，看起来像服务全挂了。</li>
 * </ol>
 */
public final class SuccessRate {

    private SuccessRate() {
    }

    /**
     * @param success 成功次数
     * @param total   总次数（<b>含降级</b>）
     * @return 百分数，保留 1 位小数；{@code total <= 0} 时返回 100.0
     */
    public static double percent(long success, long total) {
        if (total <= 0) {
            return 100.0;
        }
        // 先乘后除再四舍五入到 1 位：Math.round(x * 1000) / 10.0
        return Math.round(success * 1000.0 / total) / 10.0;
    }
}
