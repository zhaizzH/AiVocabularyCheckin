"""三个 AI 路由（前缀 ``/ai/v1``）。

**为什么合成一个文件**：S3 §3.5 写的是 ``routers/example.py`` 等「每功能一路由文件」，
但三个路由共享同一个 router、同一组异常映射与同一套依赖注入。拆成三个文件会产生
三份重复的错误映射代码（正是最容易漂移的地方）。目录语义未变（仍在 ``routers/``），
只是文件粒度更合并，不改动任何对外契约。

**异常映射统一在这里**，不在 chain 里：chain 只负责抛语义化异常，
router 负责翻译成契约要求的非 2xx + 稳定 detail。

超时预算：每个接口用 ``asyncio.timeout(settings.<feature>_timeout_s)`` 施加
**请求级总 deadline**（含模型重试、退避、解析与字段收敛）。它必须严格小于
backend 的 15/30/60s——backend 一先超时就断开连接，我们后续的重试全是白做工。
"""

from __future__ import annotations

import asyncio
import time

from fastapi import APIRouter, HTTPException, Request
from langchain_core.runnables import Runnable

from app.chains.example_chain import run_example
from app.chains.explain_chain import run_explain
from app.chains.report_graph import run_report
from app.core.config import Settings
from app.core.errors import (
    ERR_LLM_TIMEOUT,
    llm_failed_error,
    not_configured_error,
    timeout_error,
)
from app.core.logging import get_logger
from app.schemas.example import ExampleRequest, ExampleResponse
from app.schemas.explain import ExplainRequest, ExplainResponse
from app.schemas.report import ReportRequest, ReportResponse

logger = get_logger("ai_service.routes")

router = APIRouter(prefix="/ai/v1", tags=["ai"])


def _settings(request: Request) -> Settings:
    return request.app.state.settings


def _require_chain(chain: Runnable | None) -> Runnable:
    """lifespan 在缺 key 时会把 chain 置为 None；此时必须报 503，
    而不是让 ``None.ainvoke`` 抛 AttributeError 被泛化成 502。

    两者的区别对运维很重要：503 明确指向「没配 key」，
    502 会让人以为是供应商故障。
    """
    if chain is None:
        raise not_configured_error()
    return chain


def _map_exception(exc: BaseException, feature: str, cost_ms: int) -> HTTPException:
    """把任意异常翻译成稳定 detail 的 HTTPException。

    只记录异常类名，**不记录请求体**（含用户学习数据），也不打印堆栈到 stdout。
    """
    if isinstance(exc, HTTPException):
        return exc
    if isinstance(exc, (TimeoutError, asyncio.TimeoutError)):
        logger.warning(
            "ai_service.%s 调用超时 cost_ms=%d detail=%s", feature, cost_ms, ERR_LLM_TIMEOUT
        )
        return timeout_error()
    logger.warning(
        "ai_service.%s 调用失败 cost_ms=%d exc=%s", feature, cost_ms, type(exc).__name__
    )
    return llm_failed_error()


@router.post("/example", response_model=ExampleResponse)
async def generate_example(payload: ExampleRequest, request: Request) -> ExampleResponse:
    settings = _settings(request)
    started = time.monotonic()
    logger.info("ai_service.example 调用开始 word=%s context=%s", payload.word, payload.context or "-")
    try:
        chain = _require_chain(request.app.state.example_chain)
        async with asyncio.timeout(settings.example_timeout_s):
            response = await run_example(chain, payload)
    except BaseException as exc:  # noqa: BLE001 - 统一翻译后原样抛出
        raise _map_exception(exc, "example", int((time.monotonic() - started) * 1000)) from exc
    logger.info("ai_service.example 调用完成 cost_ms=%d", int((time.monotonic() - started) * 1000))
    return response


@router.post("/explain", response_model=ExplainResponse)
async def generate_explain(payload: ExplainRequest, request: Request) -> ExplainResponse:
    settings = _settings(request)
    started = time.monotonic()
    # 只记单词，不记 wrongContext（含用户学习行为）。
    logger.info("ai_service.explain 调用开始 word=%s", payload.word)
    try:
        chain = _require_chain(request.app.state.explain_chain)
        async with asyncio.timeout(settings.explain_timeout_s):
            response = await run_explain(chain, payload)
    except BaseException as exc:  # noqa: BLE001
        raise _map_exception(exc, "explain", int((time.monotonic() - started) * 1000)) from exc
    logger.info("ai_service.explain 调用完成 cost_ms=%d", int((time.monotonic() - started) * 1000))
    return response


@router.post("/report", response_model=ReportResponse)
async def generate_report(payload: ReportRequest, request: Request) -> ReportResponse:
    settings = _settings(request)
    build_graph = request.app.state.report_graph_factory
    started = time.monotonic()
    logger.info("ai_service.report 调用开始 studyDays=%d", payload.studyStats.studyDays)
    try:
        if build_graph is None:
            raise not_configured_error()
        async with asyncio.timeout(settings.report_timeout_s):
            # 图按请求构造（节点持有本 lifespan 的 LLM Runnable），
            # 避免把某个 lifespan 的 client 引用跨轮次缓存。
            graph = build_graph(payload.studyStats)
            response = await run_report(graph, payload.studyStats)
    except BaseException as exc:  # noqa: BLE001
        raise _map_exception(exc, "report", int((time.monotonic() - started) * 1000)) from exc
    logger.info("ai_service.report 调用完成 cost_ms=%d", int((time.monotonic() - started) * 1000))
    return response
