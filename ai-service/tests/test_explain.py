"""错题讲解接口（契约 §9.2）。"""

from __future__ import annotations

import httpx
from langchain_core.runnables import RunnableLambda

from app.core.errors import ERR_LLM_TIMEOUT
from tests.conftest import build_test_app, running_lifespan

VALID_EXPLAIN = (
    '{"explanation": "abandon 与 abundant 形近易混。", '
    '"advice": "把两个词并排抄写三遍，各造一句。"}'
)

BACKEND_WRONG_CONTEXT = "该词在复习中答错，累计错误 3 次"


async def _post(app, payload: dict) -> httpx.Response:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.post("/ai/v1/explain", json=payload)


async def test_explain_success(settings):
    app = build_test_app(settings, explain_responses=[VALID_EXPLAIN])
    async with running_lifespan(app):
        response = await _post(
            app,
            {
                "word": "abandon",
                "translation": "v. 放弃",
                "wrongContext": BACKEND_WRONG_CONTEXT,
            },
        )
    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"explanation", "advice"}
    assert body["explanation"]
    assert body["advice"]


async def test_explain_accepts_empty_wrong_context(settings):
    """wrongContext 允许空串：backend 可能没有更细的上下文。"""
    app = build_test_app(settings, explain_responses=[VALID_EXPLAIN])
    async with running_lifespan(app):
        response = await _post(
            app, {"word": "abandon", "translation": "v. 放弃", "wrongContext": ""}
        )
    assert response.status_code == 200


async def test_explain_timeout_maps_to_504(settings):
    """超时必须映射成 504 + 稳定 detail（backend 据此判 5001）。"""
    async def _timeout(_messages):
        raise TimeoutError("deadline exceeded")

    app = build_test_app(settings)
    async with running_lifespan(app):
        from app.chains.explain_chain import build_explain_chain

        app.state.explain_chain = build_explain_chain(
            RunnableLambda(_timeout).with_config(run_name="timeout")
        )
        response = await _post(
            app, {"word": "abandon", "translation": "v. 放弃", "wrongContext": ""}
        )
    assert response.status_code == 504
    assert response.json()["detail"] == ERR_LLM_TIMEOUT


async def test_explain_empty_field_raises_502(settings):
    app = build_test_app(
        settings, explain_responses=['{"explanation": "有内容", "advice": ""}']
    )
    async with running_lifespan(app):
        response = await _post(
            app, {"word": "abandon", "translation": "v. 放弃", "wrongContext": ""}
        )
    assert response.status_code == 502
    assert response.json()["detail"] == "ai_service_empty_result"


async def test_explain_unparseable_raises_502(settings):
    app = build_test_app(settings, explain_responses=["我也不知道怎么解释"])
    async with running_lifespan(app):
        response = await _post(
            app, {"word": "abandon", "translation": "v. 放弃", "wrongContext": ""}
        )
    assert response.status_code == 502
    assert response.json()["detail"] == "ai_service_bad_model_output"
