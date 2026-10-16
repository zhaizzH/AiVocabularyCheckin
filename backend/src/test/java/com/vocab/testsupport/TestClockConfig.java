package com.vocab.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/**
 * 测试用时钟配置：用 {@link FixedClock} 覆盖生产的 {@code ClockConfig#clock()}。
 *
 * <p>{@code @Primary} 保证注入的是本 Bean；生产代码全部按类型注入 {@code Clock}，
 * 无需任何改动。
 *
 * <p>由需要拨动日期的测试类用 {@code @Import(TestClockConfig.class)} 引入——
 * 不放进全局测试配置，避免不关心日期的用例也被固定时钟影响。
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public Clock fixedClock() {
        return new FixedClock();
    }
}
