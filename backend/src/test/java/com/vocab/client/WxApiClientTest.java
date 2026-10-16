package com.vocab.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vocab.common.BizException;
import com.vocab.common.ErrorCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信 {@code code2Session} 客户端单元测试（阶段 5）。
 *
 * <p>用 JDK 自带的 {@code com.sun.net.httpserver.HttpServer} 起一个本地桩服务，
 * 真实走一遍 HTTP（含 URL 拼接、JSON 解析、异常映射），而不是 mock 客户端方法——
 * 后者无法覆盖「参数是否真的拼进 query」「响应非 JSON 会怎样」这类跨层缺陷。
 *
 * <p>覆盖的分支：
 * <ul>
 *   <li>未配置 appid/secret → 立即 1004，且<b>不发起 HTTP 请求</b></li>
 *   <li>errcode != 0 → 1004</li>
 *   <li>响应缺 openid → 1004</li>
 *   <li>响应不是 JSON → 1004</li>
 *   <li>连接失败（端口无人监听）→ 1004</li>
 *   <li>成功 → 返回 openid，且请求参数（appid/secret/grant_type）正确</li>
 * </ul>
 */
class WxApiClientTest {

    private static HttpServer server;
    private static String baseUrl;

    /** 桩服务收到的最后一次查询串，用于断言客户端拼接正确。 */
    private static final AtomicReference<String> LAST_QUERY = new AtomicReference<>("");

    @BeforeAll
    static void startStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sns/jscode2session", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            LAST_QUERY.set(query == null ? "" : query);
            String code = extractParam(query, "js_code");

            String body;
            if (!query.contains("appid=appid-ok") || !query.contains("secret=secret-ok")) {
                // 模拟微信对错误 appid/secret 的响应
                body = "{\"errcode\":40013,\"errmsg\":\"invalid appid\"}";
            } else if ("bad-code".equals(code)) {
                body = "{\"errcode\":40029,\"errmsg\":\"invalid code\"}";
            } else if ("no-openid".equals(code)) {
                body = "{\"session_key\":\"abc\",\"unionid\":null}";
            } else if ("not-json".equals(code)) {
                body = "<html><body>502 Bad Gateway</body></html>";
            } else {
                body = "{\"openid\":\"openid-" + code + "\",\"session_key\":\"sk-" + code + "\"}";
            }

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/sns/jscode2session";
    }

    @AfterAll
    static void stopStubServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String extractParam(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private WxApiClient client(String appid, String secret) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        return new WxApiClient(new RestTemplate(factory), new ObjectMapper(),
                appid, secret, baseUrl);
    }

    private WxApiClient okClient() {
        return client("appid-ok", "secret-ok");
    }

    @Test
    @DisplayName("成功：返回 openid，且 appid/secret/grant_type 均正确拼接")
    void successReturnsOpenid() {
        String openid = okClient().code2Session("c1");
        assertEquals("openid-c1", openid);

        String query = LAST_QUERY.get();
        assertTrue(query.contains("appid=appid-ok"), "必须携带 appid，实际 query=" + query);
        assertTrue(query.contains("secret=secret-ok"), "必须携带 secret，实际 query=" + query);
        assertTrue(query.contains("js_code=c1"), "必须携带 js_code，实际 query=" + query);
        assertTrue(query.contains("grant_type=authorization_code"),
                "必须携带 grant_type=authorization_code，实际 query=" + query);
    }

    @Test
    @DisplayName("未配置 appid/secret → 1004，且不发起 HTTP 请求（桩服务收不到 query）")
    void missingCredentialsFailsFast() {
        LAST_QUERY.set("<untouched>");
        BizException e = assertThrows(BizException.class, () -> client("", "").code2Session("c1"));
        assertEquals(ErrorCode.WX_LOGIN_FAILED.getCode(), e.getCode());
        assertEquals("<untouched>", LAST_QUERY.get(), "缺凭证时不应发起 HTTP 调用");
    }

    @Test
    @DisplayName("errcode != 0（code 无效）→ 1004")
    void wechatErrorCodeMapsTo1004() {
        BizException e = assertThrows(BizException.class, () -> okClient().code2Session("bad-code"));
        assertEquals(ErrorCode.WX_LOGIN_FAILED.getCode(), e.getCode());
        assertEquals("微信登录失败", e.getMessage());
    }

    @Test
    @DisplayName("appid 不匹配（微信侧 40013）→ 1004")
    void wrongAppidMapsTo1004() {
        BizException e = assertThrows(BizException.class,
                () -> client("appid-wrong", "secret-ok").code2Session("c1"));
        assertEquals(ErrorCode.WX_LOGIN_FAILED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("响应成功但缺 openid → 1004（不能返回 null 继续建号）")
    void missingOpenidMapsTo1004() {
        BizException e = assertThrows(BizException.class, () -> okClient().code2Session("no-openid"));
        assertEquals(ErrorCode.WX_LOGIN_FAILED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("响应不是合法 JSON → 1004（不抛 9001 系统错误）")
    void nonJsonResponseMapsTo1004() {
        BizException e = assertThrows(BizException.class, () -> okClient().code2Session("not-json"));
        assertEquals(ErrorCode.WX_LOGIN_FAILED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("网络不可达 → 1004（不抛 9001）")
    void connectionFailureMapsTo1004() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(1));
        // 指向一个几乎不可能有人监听的端口
        WxApiClient dead = new WxApiClient(new RestTemplate(factory), new ObjectMapper(),
                "appid-ok", "secret-ok", "http://127.0.0.1:1/sns/jscode2session");
        BizException e = assertThrows(BizException.class, () -> dead.code2Session("c1"));
        assertEquals(ErrorCode.WX_LOGIN_FAILED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("异常消息中绝不包含 secret（防凭据泄漏到日志/响应）")
    void exceptionMessageNeverContainsSecret() {
        BizException e = assertThrows(BizException.class, () -> okClient().code2Session("bad-code"));
        assertFalse(e.getMessage().contains("secret-ok"), "异常消息泄漏了 secret");
        assertFalse(String.valueOf(e.getMessage()).contains("appid-ok"), "异常消息泄漏了 appid");
    }
}
