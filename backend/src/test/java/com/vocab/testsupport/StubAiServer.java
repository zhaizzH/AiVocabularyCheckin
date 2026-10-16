package com.vocab.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 测试用的假 ai-service：按路径返回预设 JSON。
 *
 * <p>用 JDK 内置 {@code com.sun.net.httpserver}（零依赖），让「AI 调用成功」
 * 这条路径也走<b>真实 HTTP</b>——而不是 Mock 掉 {@code AiServiceClient}。
 * 这样验证的是「请求体字段名 / 响应体字段名与契约 §9 一致」，
 * 若 Java DTO 的字段名与 ai-service 契约不符（如 {@code exampleTranslation} 拼错），
 * Mock 测试永远发现不了，真实 HTTP 测试一跑就露。
 */
public final class StubAiServer implements Closeable {

    private final HttpServer server;

    /**
     * @param responses 路径 → 响应 JSON 字符串
     */
    public StubAiServer(Map<String, String> responses, int statusCode) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, responses, statusCode));
        // 守护线程 executor：否则 HttpServer 的默认线程可能阻止 JVM 退出，
        // 让 surefire 卡在测试结束处。
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stub-ai-server");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void handle(HttpExchange exchange, Map<String, String> responses,
                               int statusCode) throws IOException {
        String body = responses.getOrDefault(exchange.getRequestURI().getPath(), "{}");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
