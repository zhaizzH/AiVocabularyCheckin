"""请求级总 deadline 与取消传播（design §6.2、§9.2）。

判据不是「客户端收到超时」，而是：
①慢 Runnable 内部确实收到 ``CancelledError``（取消到达模型边界）；
②report 超时后 ``compose`` **没有**被启动（不留后台工作）。
"""

from __future__ import annotations

import asyncio

import httpx
import pytest
from langchain_core.messages import AIMessage
from langchain_core.runnables import RunnableLambda

from app.chains.example_chain import build_example_chain
from app.chains.explain_chain import build_explain_chain
from app.chains.report_graph import build_report_graph, run_report
from app.schemas.common import StudyStats
from tests.conftest import build_test_app, running_lifespan

ANALYSIS_JSON = '{"strengths": [], "weaknesses": ["掌握度不足"], "focus": "复习错题"}'
COMPOSE_JSON = '{"overview": "o", "weakness": "w", "suggestion": "s"}'


def make_cancellable_slow_llm(
    delay_s: float, events: list[str], *, response: str = "{}"
) -> RunnableLambda:
    async def _call(_messages) -> AIMessage:
        events.append("llm_start")
        try:
            await asyncio.sleep(delay_s)
        except asyncio.CancelledError:
            events.append("llm_cancelled")
            raise
        events.append("llm_done")
        return AIMessage(content=response)

    return RunnableLambda(_call).with_config(run_name="slow_llm")


async def _post(app, path: str, payload: dict) -> httpx.Response:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.post(path, json=payload)


async def test_example_deadline_cancels_inflight_call(settings):
    events: list[str] = []
    app = build_test_app(settings)
    async with running_lifespan(app):
        app.state.example_chain = build_example_chain(
            make_cancellable_slow_llm(5.0, events)
        )
        response = await _post(
            app, "/ai/v1/example", {"word": "abandon", "translation": "v. 放弃"}
        )
    assert response.status_code == 504
    assert response.json()["detail"] == "ai_service_timeout"
    await asyncio.sleep(0.05)
    assert "llm_start" in events
    assert "llm_cancelled" in events, f"取消未到达模型边界: {events}"
    assert "llm_done" not in events, "被取消的调用不应跑完"


async def test_explain_deadline_cancels_inflight_call(settings):
    events: list[str] = []
    app = build_test_app(settings)
    async with running_lifespan(app):
        app.state.explain_chain = build_explain_chain(
            make_cancellable_slow_llm(5.0, events)
        )
        response = await _post(
            app,
            "/ai/v1/explain",
            {"word": "abandon", "translation": "v. 放弃", "wrongContext": ""},
        )
    assert response.status_code == 504
    await asyncio.sleep(0.05)
    assert "llm_cancelled" in events


async def test_report_deadline_prevents_compose_from_starting(settings):
    """report 总 deadline 到期后，第二步（compose）不得被启动。"""
    events: list[str] = []
    stats = StudyStats(
        studyDays=12,
        totalLearned=346,
        checkInRate=85,
        topMistakeWords=["abandon"],
        masteryRate=58,
    )

    async def slow_analyze(_messages) -> AIMessage:
        events.append("analyze_start")
        try:
            await asyncio.sleep(10.0)
        except asyncio.CancelledError:
            events.append("analyze_cancelled")
            raise
        return AIMessage(content=ANALYSIS_JSON)

    async def compose(_messages) -> AIMessage:
        events.append("compose_start")
        return AIMessage(content=COMPOSE_JSON)

    graph = build_report_graph(
        stats,
        RunnableLambda(slow_analyze).with_config(run_name="slow_analyze"),
        RunnableLambda(compose).with_config(run_name="compose"),
    )

    app = build_test_app(settings)
    async with running_lifespan(app):
        app.state.report_graph_factory = lambda _stats: graph
        response = await _post(app, "/ai/v1/report", {"studyStats": stats.model_dump()})

    assert response.status_code == 504
    await asyncio.sleep(0.05)
    assert "analyze_start" in events
    assert "analyze_cancelled" in events
    assert "compose_start" not in events, f"compose 不该在超时后启动: {events}"


async def test_no_background_task_left_after_timeout(settings):
    """超时后不得留存后台模型任务。"""
    events: list[str] = []
    app = build_test_app(settings)
    async with running_lifespan(app):
        app.state.example_chain = build_example_chain(
            make_cancellable_slow_llm(5.0, events)
        )
        before = len(asyncio.all_tasks())
        response = await _post(
            app, "/ai/v1/example", {"word": "abandon", "translation": "v. 放弃"}
        )
        after = len(asyncio.all_tasks())
    assert response.status_code == 504
    # 允许 ±1 的噪声（当前测试任务本身），不允许残留一个 5s 的模型任务
    assert after - before <= 1, f"疑似残留后台任务: {before} -> {after}"


async def test_run_report_propagates_timeout():
    """图自身的 ainvoke 在 asyncio.timeout 下也要能被打断。"""
    events: list[str] = []
    stats = StudyStats(
        studyDays=12,
        totalLearned=346,
        checkInRate=85,
        topMistakeWords=["abandon"],
        masteryRate=58,
    )
    graph = build_report_graph(
        stats,
        make_cancellable_slow_llm(5.0, events),
        make_cancellable_slow_llm(5.0, events),
    )
    with pytest.raises(TimeoutError):
        async with asyncio.timeout(0.2):
            await run_report(graph, stats)
    await asyncio.sleep(0.05)
    assert "llm_cancelled" in events


# ---------------------------------------------------------------------------
# 回归：异常映射的两个真实缺陷（2026-10-17 复审发现）
# ---------------------------------------------------------------------------


async def test_cancellation_is_not_translated_into_http_error(settings):
    """取消（客户端断开 / 服务停机 / 上层 timeout）不得被翻译成 HTTP 状态码。

    历史缺陷：router 用 ``except BaseException`` 捕获，把 ``CancelledError``
    映射成了 **502 ai_service_llm_failed**，并打出「模型失败」的假 WARNING。
    更严重的是吞掉取消会破坏 asyncio 取消协议，任务无法终止。
    """
    from app.chains.example_chain import build_example_chain as _build

    async def _cancelled(_messages):
        raise asyncio.CancelledError("client disconnected")

    app = build_test_app(settings)
    async with running_lifespan(app):
        app.state.example_chain = _build(
            RunnableLambda(_cancelled).with_config(run_name="cancelled")
        )
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            with pytest.raises(asyncio.CancelledError):
                await client.post(
                    "/ai/v1/example", json={"word": "abandon", "translation": "v. 放弃"}
                )


async def test_llm_attempt_timeout_maps_to_504(settings):
    """单次模型尝试超时必须映射 504，而不是 502。

    历史缺陷：``openai.APITimeoutError`` 的 MRO 不含内置 ``TimeoutError``，
    只判 ``TimeoutError`` 会让真实超时错走 502，``ai_service_timeout``
    这条稳定 detail 在单次超时时永远不出现。
    """
    import openai

    from app.chains.example_chain import build_example_chain as _build

    async def _attempt_timeout(_messages):
        raise openai.APITimeoutError(request=httpx.Request("POST", "https://api.example.invalid"))

    app = build_test_app(settings)
    async with running_lifespan(app):
        app.state.example_chain = _build(
            RunnableLambda(_attempt_timeout).with_config(run_name="attempt_timeout")
        )
        response = await _post(
            app, "/ai/v1/example", {"word": "abandon", "translation": "v. 放弃"}
        )
    assert response.status_code == 504
    assert response.json()["detail"] == "ai_service_timeout"
