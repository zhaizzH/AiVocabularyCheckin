package com.vocab.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 全局序列化配置。
 *
 * <p>契约约束（《接口详细设计》§0「字段命名」）明确规定：
 * <ul>
 *   <li>日期格式 {@code YYYY-MM-DD}</li>
 *   <li>时间格式 {@code YYYY-MM-DD HH:mm:ss}</li>
 * </ul>
 *
 * <p><b>为什么必须显式配置</b>：Jackson 对 {@code java.time} 类型的默认输出是
 * ISO-8601（{@code 2026-10-18T09:30:00}，带 {@code T} 分隔符），
 * 与契约要求的空格分隔不一致；而 {@code application.yml} 里的
 * {@code spring.jackson.date-format} <b>只作用于</b> {@code java.util.Date}，
 * 对 JSR-310 类型无效。若此处漏配，契约 §1.4 的 {@code createTime} 会输出
 * 带 {@code T} 的字符串，前端格式化会错——属于典型的跨层契约偏差。
 */
@Configuration
public class JacksonConfig {

    /** 契约 §0：时间格式。 */
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 契约 §0：日期格式。 */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            builder.serializerByType(LocalDateTime.class,
                    new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
            builder.deserializerByType(LocalDateTime.class,
                    new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));
            builder.serializerByType(LocalDate.class,
                    new LocalDateSerializer(DATE_FORMATTER));
            builder.deserializerByType(LocalDate.class,
                    new LocalDateDeserializer(DATE_FORMATTER));
        };
    }
}
