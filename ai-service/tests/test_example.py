"""例句接口（契约 §9.1）。"""

from __future__ import annotations

import httpx
from langchain_core.runnables import RunnableLambda

from tests.conftest import build_test_app, running_lifespan

VALID_EXAMPLE = (
    '{"example": "The soldiers were forced to abandon the fort.", '
    '"exampleTranslation": "士兵们被迫放弃了堡垒。", '
    '"mnemonic": "a + band + on：一群人被迫散开——放弃。"}'
)


async def _post(app, payload: dict) -> httpx.Response:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.post("/ai/v1/example", json=payload)


async def test_example_success(settings):
    app = build_test_app(settings, example_responses=[VALID_EXAMPLE])
    async with running_lifespan(app):
        response = await _post(
            app, {"word": "abandon", "translation": "v. 放弃", "context": "CET4"}
        )
    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"example", "exampleTranslation", "mnemonic"}
    assert body["example"]
    assert body["exampleTranslation"]
    assert body["mnemonic"]


async def test_example_accepts_empty_context(settings):
    """backend 的 resolveCategory 可能返回空串，不能因此 422。"""
    app = build_test_app(settings, example_responses=[VALID_EXAMPLE])
    async with running_lifespan(app):
        response = await _post(
            app, {"word": "abandon", "translation": "v. 放弃", "context": ""}
        )
    assert response.status_code == 200


async def test_example_fenced_json_is_tolerated(settings):
    fenced = "```json\n" + VALID_EXAMPLE + "\n```"
    app = build_test_app(settings, example_responses=[fenced])
    async with running_lifespan(app):
        response = await _post(app, {"word": "abandon", "translation": "v. 放弃"})
    assert response.status_code == 200
    assert response.json()["example"]


async def test_example_empty_example_field_raises_502(settings):
    """空例句是「无意义的成功」：backend 的空值兜底只在收到失败时触发。"""
    app = build_test_app(
        settings,
        example_responses=['{"example": "", "exampleTranslation": "x", "mnemonic": "y"}'],
    )
    async with running_lifespan(app):
        response = await _post(app, {"word": "abandon", "translation": "v. 放弃"})
    assert response.status_code == 502
    assert response.json()["detail"] == "ai_service_empty_result"


async def test_example_unparseable_output_raises_502(settings):
    app = build_test_app(settings, example_responses=["这不是 json"])
    async with running_lifespan(app):
        response = await _post(app, {"word": "abandon", "translation": "v. 放弃"})
    assert response.status_code == 502
    assert response.json()["detail"] == "ai_service_bad_model_output"


async def test_example_llm_failure_raises_502(settings):
    async def boom(_messages):
        raise RuntimeError("provider exploded")

    app = build_test_app(settings)
    async with running_lifespan(app):
        # chain 内部换成会抛异常的 Runnable（保留 prompt 与解析链路）
        from app.chains.example_chain import build_example_chain

        app.state.example_chain = build_example_chain(
            RunnableLambda(boom).with_config(run_name="boom")
        )
        response = await _post(app, {"word": "abandon", "translation": "v. 放弃"})
    assert response.status_code == 502
    assert response.json()["detail"] == "ai_service_llm_failed"


async def test_example_missing_key_returns_503(settings_without_key):
    """缺 key 时服务能启动，但调用期报稳定 503（而不是启动崩）。"""
    from fastapi import HTTPException
    from langchain_core.runnables import RunnableLambda

    from app.core.errors import ERR_LLM_NOT_CONFIGURED

    async def _raise(_messages):
        raise HTTPException(status_code=503, detail=ERR_LLM_NOT_CONFIGURED)

    app = build_test_app(settings_without_key, example_responses=[VALID_EXAMPLE])
    async with running_lifespan(app):
        # 真实链路中 chain 会在构造 Runnable 时抛 503；这里直接用等价 Runnable 验证映射。
        from app.chains.example_chain import build_example_chain

        app.state.example_chain = build_example_chain(
            RunnableLambda(_raise).with_config(run_name="not_configured")
        )
        response = await _post(app, {"word": "abandon", "translation": "v. 放弃"})
    assert response.status_code == 503
    assert response.json()["detail"] == ERR_LLM_NOT_CONFIGURED


async def test_example_blank_word_is_rejected(settings):
    app = build_test_app(settings, example_responses=[VALID_EXAMPLE])
    async with running_lifespan(app):
        response = await _post(app, {"word": "   ", "translation": "v. 放弃"})
    assert response.status_code == 422


async def test_missing_key_returns_503_through_real_lifespan(settings_without_key):
    """回归：缺 key 时 lifespan 把 chain 置为 None，曾因此返回 502（AttributeError 被泛化）。

    必须走**真实** lifespan 与真实 router 分支，不能用注入 fake 绕开。
    """
    from app.main import create_app

    app = create_app(settings=settings_without_key)
    async with running_lifespan(app):
        assert app.state.example_chain is None
        response = await _post(app, {"word": "abandon", "translation": "v. 放弃"})
    assert response.status_code == 503
    assert response.json()["detail"] == "ai_service_llm_not_configured"


async def test_missing_key_report_returns_503(settings_without_key):
    from app.main import create_app

    app = create_app(settings=settings_without_key)
    payload = {
        "studyStats": {
            "studyDays": 12,
            "totalLearned": 346,
            "checkInRate": 85,
            "topMistakeWords": ["abandon"],
            "masteryRate": 58,
        }
    }
    transport = httpx.ASGITransport(app=app)
    async with running_lifespan(app):
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.post("/ai/v1/report", json=payload)
    assert response.status_code == 503
    assert response.json()["detail"] == "ai_service_llm_not_configured"
