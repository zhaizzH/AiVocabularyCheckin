"""应用入口。

**必须是工厂形态**（``create_app``），不是模块级单例：

- ``httpx.AsyncClient`` 按设计只能在 lifespan 内创建（禁模块导入时创建连接池）；
- ``tests/test_llm_request.py`` 又必须把这个 client 换成 ``MockTransport`` 版本，
  才能在**离线**条件下捕获锁版 SDK 最终发出的请求体。
没有注入点，这两条要求无法同时满足。

lifespan 里创建、退出时 ``aclose()`` 的资源：共享 ``AsyncClient``、三个 feature 的
JSON-mode Runnable。**不缓存**请求/响应/生成结果——只复用连接池与无状态 Runnable。
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import httpx
from fastapi import FastAPI

from app.chains.example_chain import build_example_chain
from app.chains.explain_chain import build_explain_chain
from app.chains.report_graph import build_report_graph
from app.core.config import Settings, get_settings
from app.core.llm import (
    MAX_TOKENS_EXAMPLE,
    MAX_TOKENS_EXPLAIN,
    MAX_TOKENS_REPORT_ANALYZE,
    MAX_TOKENS_REPORT_COMPOSE,
    build_json_runnable,
)
from app.core.logging import configure_logging, get_logger
from app.routers import ai as ai_router

logger = get_logger("ai_service.main")


def create_app(
    *,
    settings: Settings | None = None,
    http_client: httpx.AsyncClient | None = None,
) -> FastAPI:
    """构造应用。

    Args:
        settings: 注入配置（测试用）；缺省时从环境读取。
        http_client: 注入共享 HTTP client（测试用 MockTransport）；缺省时由
            lifespan 创建真实的 ``httpx.AsyncClient``。
    """
    resolved_settings = settings or get_settings()
    configure_logging(resolved_settings.log_level)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        client = http_client or httpx.AsyncClient()
        app.state.settings = resolved_settings
        app.state.http_client = client

        # 每个 feature 一个绑定 JSON mode 的 Runnable；key 缺失时这里是 None，
        # 调用期由 chain 抛 503（而不是启动崩——见 config.py docstring）。
        if resolved_settings.llm_configured:
            app.state.example_chain = build_example_chain(
                build_json_runnable(
                    resolved_settings, http_async_client=client, max_tokens=MAX_TOKENS_EXAMPLE
                )
            )
            app.state.explain_chain = build_explain_chain(
                build_json_runnable(
                    resolved_settings, http_async_client=client, max_tokens=MAX_TOKENS_EXPLAIN
                )
            )
            analyze_llm = build_json_runnable(
                resolved_settings,
                http_async_client=client,
                max_tokens=MAX_TOKENS_REPORT_ANALYZE,
            )
            compose_llm = build_json_runnable(
                resolved_settings,
                http_async_client=client,
                max_tokens=MAX_TOKENS_REPORT_COMPOSE,
            )
            app.state.report_graph_factory = lambda stats: build_report_graph(
                stats, analyze_llm, compose_llm
            )
        else:
            logger.warning(
                "ai_service 未配置 DEEPSEEK_API_KEY：服务可启动，AI 接口将返回 503"
            )
            app.state.example_chain = None
            app.state.explain_chain = None
            app.state.report_graph_factory = None

        try:
            yield
        finally:
            # 必须关闭：否则进程退出时会话与连接泄漏，
            # 且重复 lifespan（测试）会累积未关闭的 client。
            if http_client is None:
                await client.aclose()
            # 清除依赖该 client 的引用，避免跨 lifespan 复用已关闭的连接池。
            app.state.example_chain = None
            app.state.explain_chain = None
            app.state.report_graph_factory = None
            app.state.http_client = None

    app = FastAPI(
        title="ai-service",
        version="1.0.0",
        description="AI 微服务：例句生成 / 错题讲解 / 学习报告（FastAPI + LangChain + DeepSeek）",
        lifespan=lifespan,
    )
    app.include_router(ai_router.router)

    @app.get("/health", tags=["ops"])
    async def health() -> dict[str, Any]:
        """健康检查。只报配置状态，**不回显 key**。"""
        return {"status": "ok", "llmConfigured": resolved_settings.llm_configured}

    return app


app = create_app()
