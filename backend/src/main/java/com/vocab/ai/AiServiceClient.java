package com.vocab.ai;

import com.vocab.ai.dto.AiExampleCallRequest;
import com.vocab.ai.dto.AiExampleCallResponse;
import com.vocab.ai.dto.AiExplainCallRequest;
import com.vocab.ai.dto.AiExplainCallResponse;
import com.vocab.ai.dto.AiReportCallRequest;
import com.vocab.ai.dto.AiReportCallResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * ai-service 的 HTTP 客户端（契约 §9）。
 *
 * <p><b>架构红线</b>（spec/directory-structure.md）：业务后端是<b>唯一</b>允许
 * 调用 ai-service 的服务，前端绝不直接请求；反之 ai-service 禁连 MySQL，
 * 所以统计数据由本服务算好、以纯数字放进请求体。
 *
 * <p><b>三个接口超时不同</b>（例句 15s / 讲解 30s / 报告 60s，契约 §9）。
 * 因此不用共享的 {@code RestTemplate}，而是基于 {@link RestTemplateBuilder}
 * 每次调用构造专用实例——共享实例只能有一组超时，无法同时满足三个值。
 *
 * <p><b>异常语义</b>：本类只抛 {@link AiCallException}（并区分 timeout/error），
 * 绝不抛 {@code BizException}。降级决策属于调用方（service 层），
 * 因为「例句要吞掉异常返回兜底」与「讲解要抛 5002」是两条不同路径。
 */
@Component
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private static final String PATH_EXAMPLE = "/ai/v1/example";
    private static final String PATH_EXPLAIN = "/ai/v1/explain";
    private static final String PATH_REPORT = "/ai/v1/report";

    private final RestTemplateBuilder restTemplateBuilder;
    private final String baseUrl;
    private final long exampleTimeoutMs;
    private final long explainTimeoutMs;
    private final long reportTimeoutMs;

    public AiServiceClient(RestTemplateBuilder restTemplateBuilder,
                           @Value("${ai.service.base-url}") String baseUrl,
                           @Value("${ai.service.example-timeout-ms}") long exampleTimeoutMs,
                           @Value("${ai.service.explain-timeout-ms}") long explainTimeoutMs,
                           @Value("${ai.service.report-timeout-ms}") long reportTimeoutMs) {
        this.restTemplateBuilder = restTemplateBuilder;
        this.baseUrl = baseUrl;
        this.exampleTimeoutMs = exampleTimeoutMs;
        this.explainTimeoutMs = explainTimeoutMs;
        this.reportTimeoutMs = reportTimeoutMs;
    }

    /** §9.1 例句生成（15s）。 */
    public AiExampleCallResponse callExample(AiExampleCallRequest request) {
        return post(PATH_EXAMPLE, request, AiExampleCallResponse.class, exampleTimeoutMs);
    }

    /** §9.2 错题讲解（30s）。 */
    public AiExplainCallResponse callExplain(AiExplainCallRequest request) {
        return post(PATH_EXPLAIN, request, AiExplainCallResponse.class, explainTimeoutMs);
    }

    /** §9.3 学习报告（60s）。 */
    public AiReportCallResponse callReport(AiReportCallRequest request) {
        return post(PATH_REPORT, request, AiReportCallResponse.class, reportTimeoutMs);
    }

    /**
     * 统一的 POST 调用 + 异常归类。
     *
     * @throws AiCallException 超时（{@code isTimeout()==true}）或其它失败（{@code false}）
     */
    private <T> T post(String path, Object body, Class<T> responseType, long timeoutMs) {
        RestTemplate restTemplate = restTemplateBuilder
                // 连接超时取两者较小值：AI 服务通常同机/同网段，建连很快。
                .setConnectTimeout(Duration.ofMillis(Math.min(5000, timeoutMs)))
                .setReadTimeout(Duration.ofMillis(timeoutMs))
                .build();

        try {
            T response = restTemplate.postForObject(baseUrl + path, body, responseType);
            if (response == null) {
                throw new AiCallException(false, "AI 服务返回空响应", null);
            }
            return response;
        } catch (RestClientException e) {
            boolean timeout = isTimeout(e);
            // 不记录请求体（可能含用户学习数据），只记异常类别与是否超时。
            log.debug("调用 ai-service 失败 path={} timeout={} type={}",
                    path, timeout, e.getClass().getSimpleName());
            throw new AiCallException(timeout, "调用 AI 服务失败: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 判定异常是否属于超时。
     *
     * <p>{@code ResourceAccessException} 同时包裹「连接超时」「读超时」「连接被拒」
     * 三种情形，cause 需逐层下钻判断；只有确属超时才算 5001，
     * 其余（含连接被拒）算异常 5002。若一律把 {@code ResourceAccessException}
     * 当超时，服务未启动时会误报 5001，与契约 §6.2「服务异常 → 5002」不符。
     *
     * <p><b>为什么必须判两种异常类型</b>（实测踩过的坑）：
     * 超时异常类型取决于底层 HTTP 客户端实现——
     * <ul>
     *   <li>{@code SimpleClientHttpRequestFactory}（{@code HttpURLConnection}）
     *       抛 {@link SocketTimeoutException}；</li>
     *   <li>Spring Boot 3.5 默认的 {@code JdkClientHttpRequestFactory}
     *       （{@code java.net.http.HttpClient}）抛
     *       {@link java.net.http.HttpTimeoutException}。</li>
     * </ul>
     * 二者都是 {@code IOException} 的子类但<b>互不继承</b>（是兄弟关系）。
     * 只判 {@code SocketTimeoutException} 会让生产环境（走 JDK HttpClient）的
     * 真实读超时被误判为 5002。本方法是超时判定的<b>唯一真相源</b>，
     * 故两种都判——这样无论底层换成哪个实现，5001 语义都不变。
     */
    private boolean isTimeout(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
