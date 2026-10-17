"""lifespan 资源生命周期（design §5 第 6/9 点、§9.2）。

必须用 ``app.router.lifespan_context(app)`` 手动驱动：裸 ``ASGITransport``
不会触发 startup/shutdown，用它写的 lifespan 测试会「全绿但什么都没测」。
"""

from __future__ import annotations

import httpx

from app.main import create_app
from tests.conftest import running_lifespan


async def test_client_created_and_closed_per_lifespan(settings):
    app = create_app(settings=settings)
    # 用真实 lifespan（不注入 http_client），验证它自己创建/关闭 client。
    for _ in range(2):
        async with running_lifespan(app):
            client = app.state.http_client
            assert client is not None
            assert client.is_closed is False
        # 退出后必须已关闭
        assert client.is_closed is True
        # 依赖该 client 的引用必须被清除
        assert app.state.example_chain is None
        assert app.state.explain_chain is None
        assert app.state.report_graph_factory is None
        assert app.state.http_client is None


async def test_each_lifespan_builds_fresh_client(settings):
    app = create_app(settings=settings)
    clients = []
    for _ in range(2):
        async with running_lifespan(app):
            clients.append(app.state.http_client)
    assert clients[0] is not clients[1], "不得跨 lifespan 复用同一个 client"
    assert all(client.is_closed for client in clients)


async def test_injected_client_is_not_closed_by_app(settings):
    """注入的 client 由调用方拥有（测试/上层容器管理），应用不擅自关闭。"""
    injected = httpx.AsyncClient()
    app = create_app(settings=settings, http_client=injected)
    async with running_lifespan(app):
        assert app.state.http_client is injected
    assert injected.is_closed is False
    await injected.aclose()


async def test_missing_key_yields_no_chains_but_app_starts(settings_without_key):
    app = create_app(settings=settings_without_key)
    async with running_lifespan(app):
        assert app.state.example_chain is None
        assert app.state.explain_chain is None
        assert app.state.report_graph_factory is None
        # 服务本身仍然可用
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            health = await client.get("/health")
        assert health.status_code == 200
        assert health.json()["llmConfigured"] is False
