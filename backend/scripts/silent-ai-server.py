#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
「哑 ai-service」：监听端口但永不响应，用于在 G5 闸口真实验证
「AI 服务超时 → 5001（而不是 5002）」这条路径。

用真实 TCP 挂起（而非 Mock 造假异常）才能暴露底层 HTTP 客户端的
真实异常类型——实测 Spring Boot 3.5 默认 JdkClientHttpRequestFactory
抛的是 java.net.http.HttpTimeoutException，与 SocketTimeoutException
互不继承，只判后者会把生产超时误报为 5002。

用法：python backend/scripts/silent-ai-server.py [port]   （默认 8000）
"""
import socket
import sys
import threading

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8000

server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(("127.0.0.1", PORT))
server.listen(50)
print("silent-ai-server listening on 127.0.0.1:%d (never responds)" % PORT, flush=True)

held = []


def hold(conn):
    # 读掉请求但绝不写响应，让客户端读超时。
    try:
        conn.recv(65536)
    except OSError:
        pass
    held.append(conn)  # 保活，保持连接处于「已建立但无响应」状态


while True:
    conn, _ = server.accept()
    threading.Thread(target=hold, args=(conn,), daemon=True).start()
