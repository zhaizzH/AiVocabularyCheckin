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
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import openai
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

    ⚠️ 调用方必须**先过滤 ``CancelledError``**（见 ``_handle``）：取消不是失败，
    既不能映射成 HTTP 状态码，也不能被吞掉——asyncio 的取消协议要求它继续上抛。
    """
    if isinstance(exc, HTTPException):
        return exc
    # ``openai.APITimeoutError`` 的 MRO 是 APIConnectionError → APIError →
    # OpenAIError → Exception，**不继承** 内置 ``TimeoutError``（实测）。
    # 只判 TimeoutError 会让「单次尝试超时」错走 502，而契约里超时必须能
    # 对外表现为 504 + ai_service_timeout。
    if isinstance(exc, (TimeoutError, asyncio.TimeoutError, openai.APITimeoutError)):
        logger.warning(
            "ai_service.%s 调用超时 cost_ms=%d detail=%s", feature, cost_ms, ERR_LLM_TIMEOUT
        )
        return timeout_error()
    logger.warning(
        "ai_service.%s 调用失败 cost_ms=%d exc=%s", feature, cost_ms, type(exc).__name__
    )
    return llm_failed_error()


@asynccontextmanager
async def _handle(feature: str, started: float) -> AsyncIterator[None]:
    """把「执行 + 统一异常映射」收敛成一处，避免三个路由各抄一份（易漂移）。

    **取消必须原样上抛**：客户端断开、uvicorn 停机、上层 ``asyncio.timeout`` 都会
    以 ``CancelledError`` 表达。若像 ``except BaseException`` 那样一并转成 HTTPException，
    会有三个后果：①把正常取消记成「模型故障」的假 WARNING；②返回 502 让 backend
    误判供应商故障；③最严重的是吞掉取消会让任务无法终止（破坏 asyncio 取消协议）。
    """
    try:
        yield
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        raise _map_exception(exc, feature, int((time.monotonic() - started) * 1000)) from exc


@router.post("/example", response_model=ExampleResponse)
async def generate_example(payload: ExampleRequest, request: Request) -> ExampleResponse:
    settings = _settings(request)
    started = time.monotonic()
    logger.info("ai_service.example 调用开始 word=%s context=%s", payload.word, payload.context or "-")
    async with _handle("example", started):
        chain = _require_chain(request.app.state.example_chain)
        async with asyncio.timeout(settings.example_timeout_s):
            response = await run_example(chain, payload)
    logger.info("ai_service.example 调用完成 cost_ms=%d", int((time.monotonic() - started) * 1000))
    return response


@router.post("/explain", response_model=ExplainResponse)
async def generate_explain(payload: ExplainRequest, request: Request) -> ExplainResponse:
    settings = _settings(request)
    started = time.monotonic()
    # 只记单词，不记 wrongContext（含用户学习行为）。
    logger.info("ai_service.explain 调用开始 word=%s", payload.word)
    async with _handle("explain", started):
        chain = _require_chain(request.app.state.explain_chain)
        async with asyncio.timeout(settings.explain_timeout_s):
            response = await run_explain(chain, payload)
    logger.info("ai_service.explain 调用完成 cost_ms=%d", int((time.monotonic() - started) * 1000))
    return response


@router.post("/report", response_model=ReportResponse)
async def generate_report(payload: ReportRequest, request: Request) -> ReportResponse:
    settings = _settings(request)
    build_graph = request.app.state.report_graph_factory
    started = time.monotonic()
    logger.info("ai_service.report 调用开始 studyDays=%d", payload.studyStats.studyDays)
    async with _handle("report", started):
        if build_graph is None:
            raise not_configured_error()
        async with asyncio.timeout(settings.report_timeout_s):
            # 图按请求构造（节点持有本 lifespan 的 LLM Runnable），
            # 避免把某个 lifespan 的 client 引用跨轮次缓存。
            graph = build_graph(payload.studyStats)
            response = await run_report(graph, payload.studyStats)
    logger.info("ai_service.report 调用完成 cost_ms=%d", int((time.monotonic() - started) * 1000))
    return response
