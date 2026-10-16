package com.vocab.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SuccessRate} 纯函数测试（契约 §8.10 成功率口径）。
 *
 * <p>这些用例的价值在于<b>锁死两条最容易被改错的口径</b>：
 * <ul>
 *   <li>分母含降级——否则「成功率」永远 100%，看起来健康但毫无信息量；</li>
 *   <li>无调用时 100 而非 0——新部署的管理端看板不应显示成「服务全挂」。</li>
 * </ul>
 */
class SuccessRateTest {

    @Test
    @DisplayName("普通比例：保留 1 位小数（2/3 → 66.7）")
    void roundsToOneDecimal() {
        assertEquals(66.7, SuccessRate.percent(2, 3), 1e-9);
        assertEquals(96.2, SuccessRate.percent(962, 1000), 1e-9);
        assertEquals(0.5, SuccessRate.percent(1, 200), 1e-9);
    }

    @Test
    @DisplayName("分母含降级：1 降级 + 1 成功 → 50%（不是 100%）")
    void denominatorIncludesDegrades() {
        assertEquals(50.0, SuccessRate.percent(1, 2), 1e-9);
        assertEquals(33.3, SuccessRate.percent(1, 3), 1e-9);
    }

    @Test
    @DisplayName("全部成功 → 100")
    void allSuccess() {
        assertEquals(100.0, SuccessRate.percent(5, 5), 1e-9);
    }

    @Test
    @DisplayName("无调用（total=0）→ 100，语义是「没有失败」而非「0% 成功率」")
    void zeroCallsIsHundred() {
        assertEquals(100.0, SuccessRate.percent(0, 0), 1e-9);
        assertEquals(100.0, SuccessRate.percent(0, -1), 1e-9);
    }

    @Test
    @DisplayName("全部降级 → 0")
    void allDegraded() {
        assertEquals(0.0, SuccessRate.percent(0, 7), 1e-9);
    }
}
