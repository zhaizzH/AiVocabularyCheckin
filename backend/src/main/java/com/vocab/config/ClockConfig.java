package com.vocab.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时钟 Bean。
 *
 * <p><b>为什么不直接调 {@code LocalDate.now()}</b>：
 * 学习计划、打卡、间隔复习这三处的全部语义都围绕「今天」展开
 * （今日计划、今日打卡、到期复习）。若时间源硬编码在业务类里，
 * 「跨天连续打卡」「到期复习」这类用例就只能靠改系统时钟或直接改库来测。
 *
 * <p>本项目统一从本 Bean 取时间，单测可用 {@code Clock.fixed(...)}
 * 注入固定时间，不需要依赖实际日期。
 */
@Configuration
public class ClockConfig {

    /** 默认时钟（本地时区，与 {@code serverTimezone=Asia/Shanghai} 一致）。 */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
