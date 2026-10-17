"""健康检查与 OpenAPI。"""

from __future__ import annotations

import httpx

from app.main import create_app
from tests.conftest import running_lifespan


async def _get(app, path: str) -> httpx.Response:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.get(path)


async def test_health_reports_config_without_leaking_key(settings):
    app = create_app(settings=settings)
    async with running_lifespan(app):
        response = await _get(app, "/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["llmConfigured"] is True
    # 绝不回显 key
    assert "test-key-not-real" not in response.text
    assert set(body) == {"status", "llmConfigured"}


async def test_health_reflects_missing_key(settings_without_key):
    app = create_app(settings=settings_without_key)
    async with running_lifespan(app):
        response = await _get(app, "/health")
    assert response.status_code == 200
    assert response.json()["llmConfigured"] is False
    assert "test-key-not-real" not in response.text


async def test_openapi_exposes_three_routes(settings):
    app = create_app(settings=settings)
    async with running_lifespan(app):
        response = await _get(app, "/openapi.json")
    assert response.status_code == 200
    paths = response.json()["paths"]
    for path in ("/ai/v1/example", "/ai/v1/explain", "/ai/v1/report"):
        assert path in paths, path
