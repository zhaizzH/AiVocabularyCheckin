"""学习报告接口（契约 §9.3）：条件路由、快速路径、失败路径。"""

from __future__ import annotations

import httpx
from langchain_core.runnables import RunnableLambda

from app.chains.report_graph import build_report_graph, run_report
from app.schemas.common import StudyStats
from tests.conftest import build_test_app, make_fake_llm, make_recording_fake_llm, running_lifespan

ANALYSIS_JSON = (
    '{"strengths": ["坚持打卡，近两周打卡率 85%"], '
    '"weaknesses": ["掌握度 58%，错题仍有约 4 成未掌握"], '
    '"focus": "优先复习 abandon 这类高频错误词"}'
)
COMPOSE_JSON = (
    '{"overview": "你已坚持学习 12 天，累计学习 346 个单词。", '
    '"weakness": "错题中仍有约 4 成未掌握，abandon 等词反复出错。", '
    '"suggestion": "本周先把高频错误词并排对比记忆，再回到错题本重做一遍。"}'
)

NORMAL_STATS = {
    "studyDays": 12,
    "totalLearned": 346,
    "checkInRate": 85,
    "topMistakeWords": ["abandon", "ambiguous"],
    "masteryRate": 58,
}


async def _post(app, payload: dict) -> httpx.Response:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.post("/ai/v1/report", json=payload)


async def test_report_normal_path_calls_llm_twice(settings):
    app = build_test_app(
        settings, analyze_responses=[ANALYSIS_JSON], compose_responses=[COMPOSE_JSON]
    )
    async with running_lifespan(app):
        response = await _post(app, {"studyStats": NORMAL_STATS})
        analyze_calls, compose_calls = app.state.report_calls
    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"overview", "weakness", "suggestion"}
    assert all(body.values())
    assert len(analyze_calls) == 1
    assert len(compose_calls) == 1


async def test_report_fast_path_calls_llm_zero_times(settings):
    """无错题且掌握度 100 → 确定性快速路径，不调用模型。"""
    stats = {
        "studyDays": 5,
        "totalLearned": 50,
        "checkInRate": 100,
        "topMistakeWords": [],
        "masteryRate": 100,
    }
    analyze_llm, analyze_calls = make_recording_fake_llm([])
    compose_llm, compose_calls = make_recording_fake_llm([])
    graph = build_report_graph(StudyStats(**stats), analyze_llm, compose_llm)
    result = await run_report(graph, StudyStats(**stats))

    assert result.overview and result.weakness and result.suggestion
    assert analyze_calls == []
    assert compose_calls == []
    # 不得夸大成「已掌握全部学习内容」
    assert "全部学习内容" not in result.weakness
    assert "暂无明显薄弱错题" in result.weakness


async def test_report_empty_words_but_mastery_below_100_still_uses_llm(settings):
    """空错题列表不等于整体已掌握：掌握度非 100 时必须走常规路径。"""
    stats = {
        "studyDays": 12,
        "totalLearned": 346,
        "checkInRate": 85,
        "topMistakeWords": [],
        "masteryRate": 80,
    }
    analyze_llm, analyze_calls = make_recording_fake_llm([ANALYSIS_JSON])
    compose_llm, compose_calls = make_recording_fake_llm([COMPOSE_JSON])
    graph = build_report_graph(StudyStats(**stats), analyze_llm, compose_llm)
    await run_report(graph, StudyStats(**stats))
    assert len(analyze_calls) == 1
    assert len(compose_calls) == 1


async def test_report_state_contains_analysis_and_report(settings):
    """total=False 下写错键会静默丢数据，故断言两个键都在最终 state。"""
    stats = StudyStats(**NORMAL_STATS)
    graph = build_report_graph(
        stats,
        make_fake_llm([ANALYSIS_JSON]),
        make_fake_llm([COMPOSE_JSON]),
    )
    state = await graph.ainvoke({"stats": stats.model_dump()})
    assert "analysis" in state
    assert state["analysis"]["focus"]
    assert "report" in state
    assert set(state["report"]) == {"overview", "weakness", "suggestion"}


async def test_report_analyze_failure_raises_502(settings):
    async def boom(_messages):
        raise RuntimeError("analyze exploded")

    stats = StudyStats(**NORMAL_STATS)
    graph = build_report_graph(
        stats,
        RunnableLambda(boom).with_config(run_name="boom"),
        make_fake_llm([COMPOSE_JSON]),
    )
    app = build_test_app(settings)
    async with running_lifespan(app):
        app.state.report_graph_factory = lambda _stats: graph
        response = await _post(app, {"studyStats": NORMAL_STATS})
    assert response.status_code == 502
    assert response.json()["detail"] == "ai_service_llm_failed"


async def test_report_compose_without_analysis_fails_loudly(settings):
    """compose 拿不到 analysis 时必须报错，不能静默产出空字段报告。"""
    from app.chains.report_graph import make_compose_node

    compose = make_compose_node(make_fake_llm([COMPOSE_JSON]))
    try:
        await compose({"stats": NORMAL_STATS})
        raise AssertionError("expected internal state error")
    except Exception as exc:  # noqa: BLE001
        assert getattr(exc, "status_code", None) == 500
        assert getattr(exc, "detail", None) == "ai_service_internal_state"


async def test_report_unparseable_compose_raises_502(settings):
    stats = StudyStats(**NORMAL_STATS)
    graph = build_report_graph(
        stats, make_fake_llm([ANALYSIS_JSON]), make_fake_llm(["完全不是 json"])
    )
    app = build_test_app(settings)
    async with running_lifespan(app):
        app.state.report_graph_factory = lambda _stats: graph
        response = await _post(app, {"studyStats": NORMAL_STATS})
    assert response.status_code == 502
    assert response.json()["detail"] == "ai_service_bad_model_output"


async def test_analyze_rejects_empty_analysis(settings):
    """analyze 返回既无 focus 又无 weaknesses 时，属于「无内容成功」，必须报错。"""
    from fastapi import HTTPException

    from app.chains.report_graph import make_analyze_node

    analyze = make_analyze_node(make_fake_llm(['{"strengths": [], "weaknesses": [], "focus": ""}']))
    try:
        await analyze({"stats": NORMAL_STATS})
        raise AssertionError("expected internal state error")
    except HTTPException as exc:
        assert exc.status_code == 500
        assert exc.detail == "ai_service_internal_state"


async def test_analyze_coerces_types_into_internal_model():
    """模型返回非预期类型时收敛，不把脏数据带进 compose。"""
    from app.chains.report_graph import make_analyze_node

    analyze = make_analyze_node(
        make_fake_llm(['{"strengths": "单条字符串", "weaknesses": [1, " x "], "focus": 42}'])
    )
    state = await analyze({"stats": NORMAL_STATS})
    analysis = state["analysis"]
    assert analysis["strengths"] == ["单条字符串"]
    assert analysis["weaknesses"] == ["1", "x"]
    assert analysis["focus"] == "42"
