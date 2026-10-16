package com.vocab.testsupport;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 测试用的「哑服务器」：接受 TCP 连接但<b>永不响应</b>，用于真实触发读超时。
 *
 * <p><b>为什么不用 Mock 模拟超时</b>：G5 闸口的核心命题是
 * 「ai-service 未启动/不响应时，降级路径全绿」。若用 Mock 让
 * {@code RestTemplate} 抛一个手工构造的 {@code ResourceAccessException}，
 * 那验证的是「我对超时的想象」，而不是「真实网络超时下代码的行为」。
 * 本类提供一个真实会挂起的对端，{@code SimpleClientHttpRequestFactory}
 * 会在读超时后抛出真实的 {@code SocketTimeoutException}。
 *
 * <p>这能发现纯 Mock 测不出的问题，例如：异常包装层次不同导致
 * {@code isTimeout()} 判定错误、连接超时与读超时被混淆等。
 */
public final class SilentHttpServer implements Closeable {

    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private volatile boolean running = true;

    public SilentHttpServer() throws IOException {
        this.serverSocket = new ServerSocket(0);
        this.acceptThread = new Thread(this::acceptLoop, "silent-http-server");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    /** 监听端口，供拼 {@code http://127.0.0.1:port} 使用。 */
    public int port() {
        return serverSocket.getLocalPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    private void acceptLoop() {
        while (running) {
            try {
                // 接受连接但什么都不写：客户端读超时。
                // 连接本身保存在局部变量里直到下次循环——足以让对端保持「已连接」状态。
                @SuppressWarnings("unused")
                Socket socket = serverSocket.accept();
            } catch (IOException e) {
                // 关闭 serverSocket 时 accept 会抛异常，这是正常的退出路径。
                return;
            }
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        serverSocket.close();
    }
}
