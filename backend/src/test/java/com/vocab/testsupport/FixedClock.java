package com.vocab.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 可在测试中拨动的时钟。
 *
 * <p>「今天」是学习/打卡/复习三个模块的核心变量，而「跨天」「昨天打过卡」
 * 这类用例无法在真实时钟下稳定覆盖。本类把当前日期放在一个静态字段里，
 * 用例可随时 {@link #setDate(LocalDate)} 拨到任意一天——无需改系统时间，
 * 也无需直接改库。
 *
 * <p>由 {@code TestClockConfig} 以 {@code @Primary} 注册，覆盖生产环境的
 * {@code ClockConfig#clock()}。
 *
 * <p>注意：静态状态在用例之间会残留，故 {@code @BeforeEach}/{@code @AfterEach}
 * 必须显式重置（{@link #reset()}）。
 */
public final class FixedClock extends Clock {

    /** 默认「今天」——固定值，保证用例不随运行日期漂移。 */
    public static final LocalDate DEFAULT_DATE = LocalDate.of(2026, 10, 16);

    /** 时区取与 {@code serverTimezone=Asia/Shanghai} 一致，避免日期偏移。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static volatile LocalDate currentDate = DEFAULT_DATE;

    public static void setDate(LocalDate date) {
        currentDate = date;
    }

    public static void reset() {
        currentDate = DEFAULT_DATE;
    }

    public static LocalDate currentDate() {
        return currentDate;
    }

    @Override
    public ZoneId getZone() {
        return ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        // 固定时钟不随 zone 变换日期，直接返回自身（保持行为可预期）。
        return this;
    }

    @Override
    public Instant instant() {
        return currentDate.atStartOfDay(ZONE).toInstant();
    }
}
