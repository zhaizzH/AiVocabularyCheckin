package com.vocab.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 调用 ai-service 的 HTTP 客户端配置。
 *
 * <p>架构约束（《接口详细设计》§9 + spec/directory-structure.md）：
 * 业务后端是<b>唯一</b>允许调用 ai-service 的服务；前端绝不直接请求。
 *
 * <p>本类提供的是<b>默认</b>实例（较短超时，用于连接探测）。
 * 三个 AI 功能的实际超时不同（例句 15s / 讲解 30s / 报告 60s），
 * 由 {@code AiService} 在调用时基于 {@code RestTemplateBuilder} 按需构造专用实例，
 * 见 design.md §6.1。
 *
 * <p>关键点：必须有<b>显式超时</b>，绝不能让 AI 请求无限挂起而阻塞学习类接口。
 */
@Configuration
public class RestTemplateConfig {

    /** 默认连接超时：与 AI 服务建连一般很快，5s 足够。 */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 默认读超时：取三接口中最小值，避免误用为长耗时调用。 */
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(15);

    /** 微信 code2Session 专用读超时。 */
    private static final Duration WX_READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public RestTemplate restTemplate() {
        return build(DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    /**
     * 微信 {@code code2Session} 专用客户端。
     *
     * <p>为什么不复用上面的默认实例：该调用位于<b>登录主链路</b>上，
     * 用户在前端等待结果。默认 15s 读超时太长——微信侧异常时用户会干等 15 秒。
     * 这里收紧到 5s，快速失败并由前端提示重试。
     */
    @Bean("wxRestTemplate")
    public RestTemplate wxRestTemplate() {
        return build(DEFAULT_CONNECT_TIMEOUT, WX_READ_TIMEOUT);
    }

    private RestTemplate build(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }
}
