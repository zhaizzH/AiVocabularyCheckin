package com.vocab.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 掌握度公式单测（契约 §7.2 / §9.3 共用的唯一真相源）。
 *
 * <p>纯函数，无需 Spring 上下文——这类计算规则本就该能脱离框架验证。
 */
class MasteryRatesTest {

    @Test
    @DisplayName("常规比例：2/4 = 50、1/4 = 25、3/4 = 75")
    void computesPlainRatios() {
        assertEquals(50, MasteryRates.percent(2, 4));
        assertEquals(25, MasteryRates.percent(1, 4));
        assertEquals(75, MasteryRates.percent(3, 4));
    }

    @Test
    @DisplayName("四舍五入：1/3 → 33、2/3 → 67（不是截断成 33/66）")
    void roundsHalfUp() {
        assertEquals(33, MasteryRates.percent(1, 3));
        assertEquals(67, MasteryRates.percent(2, 3));
        assertEquals(86, MasteryRates.percent(6, 7));
    }

    @Test
    @DisplayName("全掌握 → 100；零错题 → 100（语义是「没有未掌握的薄弱点」）")
    void returns100WhenNothingUnmastered() {
        assertEquals(100, MasteryRates.percent(5, 5));
        assertEquals(100, MasteryRates.percent(0, 0),
                "零错题取 100：若取 0 会被前端解读为「一个都没掌握」");
    }

    @Test
    @DisplayName("边界：0/N → 0，N/N → 100，分母为负（异常输入）→ 100 而非除零崩溃")
    void handlesEdgeInputs() {
        assertEquals(0, MasteryRates.percent(0, 10));
        assertEquals(100, MasteryRates.percent(10, 10));
        assertEquals(100, MasteryRates.percent(0, -1),
                "负分母按空集合处理，绝不能抛 ArithmeticException");
    }
}
