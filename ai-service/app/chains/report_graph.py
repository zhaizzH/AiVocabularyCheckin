"""学习报告：带确定性条件路由的多步编排（``langgraph.StateGraph``）。

为什么用图而不是单次调用：
契约要求报告分三段且互相呼应（``weakness`` 要来自数据、``suggestion`` 要针对
``weakness``）。单次调用容易写出一段通用鸡汤；先分析再生成，能让 ``suggestion``
有条件地引用分析结论。代价是两次模型调用，但 report 有 60s 预算，可以承受。

为什么只给 report 用图：
example/explain 是「一次调用 + 一次解析」，上图只有 1 节点 1 边，是仪式性复杂度。
S3 §3.5 要求的是「多步 Chain」，不是「所有东西都上图」。

流程：

    digest（纯 Python，非 LLM）
        ├─ topMistakeWords 为空 且 masteryRate == 100  → 直接产出完整报告 → END（0 次 LLM）
        └─ 其他情况 → analyze（LLM #1）→ compose（LLM #2）→ END

快速路径的语义边界：``masteryRate`` 在「无错题」时为 100（backend 的
``MasteryRates.percent`` 在分母为 0 时返回 100）。因此该分支实际命中
「从未答错过任何词」的用户。此时只能说「当前统计中暂无明显薄弱错题」，
**不得**夸大成「已掌握全部学习内容」——快速路径的文案因此写死为中性表述。
它也不是模型故障时的降级替代：它是按已知统计事实生成的契约内确定性结果。
"""

from __future__ import annotations

from typing import Any, TypedDict

from langchain_core.output_parsers import StrOutputParser
from langchain_core.runnables import Runnable
from langgraph.graph import END, StateGraph

from app.chains.parsing import converge_fields, parse_json_object, require_non_empty
from app.chains.prompts import report_analyze_prompt, report_compose_prompt
from app.core.errors import internal_state_error
from app.schemas.common import StudyStats
from app.schemas.report import ReportAnalysis, ReportResponse

_REPORT_FIELDS = ("overview", "weakness", "suggestion")


class ReportState(TypedDict, total=False):
    """图状态。

    ``total=False`` 让每个节点只写自己那份键，但代价是**写错键名会静默丢数据**，
    所以：①节点必须返回完整增量；②单测断言 ``analysis`` 与 ``report`` 都在最终 state；
    ③``compose`` 入口显式校验 ``analysis`` 存在且非空。
    """

    stats: dict[str, Any]
    digest: str
    route: str
    analysis: dict[str, Any]
    report: dict[str, str]


def _format_words(words: list[str]) -> str:
    return "、".join(words) if words else "（无）"


def _str_list(value: Any) -> list[str]:
    """容错地把模型返回值收敛成字符串列表。

    模型偶尔返回 ``"a;b"`` 或 ``[1, 2]``；这里只做类型收敛，不做语义猜测
    （不按标点拆分——拆错会把一条结论变成两条，影响 compose 的判断）。
    """
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if isinstance(value, str) and value.strip():
        return [value.strip()]
    return []


def make_digest_node() -> Any:
    """构造 ``digest`` 节点：纯 Python 格式化 + 数据可用性判断，不调用模型。"""

    def digest(state: ReportState) -> dict[str, Any]:
        stats = state["stats"]
        words: list[str] = stats.get("topMistakeWords") or []
        mastery = int(stats.get("masteryRate") or 0)
        study_days = int(stats.get("studyDays") or 0)
        total_learned = int(stats.get("totalLearned") or 0)
        check_in_rate = int(stats.get("checkInRate") or 0)

        summary = (
            f"studyDays={study_days}; totalLearned={total_learned}; "
            f"checkInRate={check_in_rate}; masteryRate={mastery}; "
            f"topMistakeWords={_format_words(words)}"
        )

        # 快速路径：仅当「无错题」且「掌握度 100」时成立。
        # 不得仅凭空错题列表就断言用户整体已掌握。
        if not words and mastery == 100:
            overview = (
                f"你已累计学习 {study_days} 天，学习过 {total_learned} 个单词，"
                f"近两周打卡率 {check_in_rate}%。"
            )
            weakness = "当前统计中暂无明显薄弱错题。"
            suggestion = (
                "可以继续保持现有的复习节奏，并适当增加新词的学习量；"
                "建议定期回顾已学单词，避免遗忘。"
            )
            return {
                "digest": summary,
                "route": "complete",
                "report": {
                    "overview": overview,
                    "weakness": weakness,
                    "suggestion": suggestion,
                },
            }

        return {"digest": summary, "route": "analyze"}

    return digest


def make_analyze_node(json_llm: Runnable) -> Any:
    """构造 ``analyze`` 节点（LLM #1）：产出内部结构化结论。"""
    chain = report_analyze_prompt | json_llm | StrOutputParser()

    async def analyze(state: ReportState) -> dict[str, Any]:
        stats = state["stats"]
        raw = await chain.ainvoke(
            {
                "study_days": stats.get("studyDays", 0),
                "total_learned": stats.get("totalLearned", 0),
                "check_in_rate": stats.get("checkInRate", 0),
                "mastery_rate": stats.get("masteryRate", 0),
                "top_mistake_words": _format_words(stats.get("topMistakeWords") or []),
            }
        )
        payload = parse_json_object(raw)
        # 用内部 Pydantic 模型校验：结构不对就在这里失败，
        # 而不是把半成品 dict 塞进 compose 的提示词。
        analysis = ReportAnalysis(
            strengths=_str_list(payload.get("strengths")),
            weaknesses=_str_list(payload.get("weaknesses")),
            focus=str(payload.get("focus") or ""),
        )
        if not analysis.focus and not analysis.weaknesses:
            raise internal_state_error()
        return {"analysis": analysis.model_dump()}

    return analyze


def make_compose_node(json_llm: Runnable) -> Any:
    """构造 ``compose`` 节点（LLM #2）：基于分析结论生成契约三段。"""
    chain = report_compose_prompt | json_llm | StrOutputParser()

    async def compose(state: ReportState) -> dict[str, Any]:
        # 显式校验：total=False 下若上游键名写错，这里能立刻发现，
        # 而不是把空 analysis 静默塞进提示词、生成一份无依据的报告。
        analysis = state.get("analysis")
        if not analysis or not isinstance(analysis, dict):
            raise internal_state_error()

        stats = state["stats"]
        import json as _json

        raw = await chain.ainvoke(
            {
                "study_days": stats.get("studyDays", 0),
                "total_learned": stats.get("totalLearned", 0),
                "check_in_rate": stats.get("checkInRate", 0),
                "mastery_rate": stats.get("masteryRate", 0),
                "top_mistake_words": _format_words(stats.get("topMistakeWords") or []),
                "analysis": _json.dumps(analysis, ensure_ascii=False),
            }
        )
        payload = parse_json_object(raw)
        converged = converge_fields(payload, _REPORT_FIELDS)
        require_non_empty(converged, *_REPORT_FIELDS)
        return {"report": converged}

    return compose


def build_report_graph(
    stats: StudyStats, analyze_llm: Runnable, compose_llm: Runnable
) -> Any:
    """编译报告图。

    ``analyze_llm`` / ``compose_llm`` 必须是已绑定 JSON mode 的 Runnable，
    由调用方按当前 lifespan 的 HTTP client 注入。
    """
    graph: StateGraph = StateGraph(ReportState)
    graph.add_node("digest", make_digest_node())
    graph.add_node("analyze", make_analyze_node(analyze_llm))
    graph.add_node("compose", make_compose_node(compose_llm))
    graph.set_entry_point("digest")
    graph.add_conditional_edges(
        "digest",
        lambda state: state["route"],
        {"complete": END, "analyze": "analyze"},
    )
    graph.add_edge("analyze", "compose")
    graph.add_edge("compose", END)
    return graph.compile()


async def run_report(graph: Any, stats: StudyStats) -> ReportResponse:
    """执行图并收敛成契约响应。"""
    final_state: ReportState = await graph.ainvoke({"stats": stats.model_dump()})
    report = final_state.get("report")
    if not report:
        # 图跑完却没有 report：编排 bug 或 route 写错，绝不能返回空字段糊过去。
        raise internal_state_error()
    converged = converge_fields(report, _REPORT_FIELDS)
    require_non_empty(converged, *_REPORT_FIELDS)
    return ReportResponse(**converged)
