"""测试夹具。

核心策略（design §9.1）：**只替换 LLM，保留真实链路**。
FakeLLM 只返回预设的 ``AIMessage``，prompt 渲染 → JSON 解析 → 字段收敛 →
Pydantic 校验全部走真实代码。这样 mock 不会掩盖提示词或解析的 bug。

注意两个坑（见 research §6.7 / §6.8）：
- ``pytest.ini`` 必须设 ``asyncio_mode = auto``，否则全部 async 用例被 skip 且退出码 0；
- 裸 ``httpx.ASGITransport`` **不执行 lifespan**，故需要 lifespan 的用例必须
  手动 ``app.router.lifespan_context(app)`` 进/出。
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import httpx
import pytest
from langchain_core.messages import AIMessage
from langchain_core.runnables import RunnableLambda
from fastapi import FastAPI

from app.core.config import Settings

# 假 key：只用于让 chat model 能构造，永不出网（所有用例都替换了 LLM Runnable）。
# 刻意不带 sk- 前缀，避免仓库红线自查误命中。
FAKE_API_KEY = "test-key-not-real"
TEST_BASE_URL = "https://api.example.invalid"


@pytest.fixture
def settings() -> Settings:
    return Settings(
        deepseek_api_key=FAKE_API_KEY,
        deepseek_base_url=TEST_BASE_URL,
        deepseek_model="deepseek-flash",
        llm_attempt_timeout_s=0.5,
        example_timeout_s=1.0,
        explain_timeout_s=1.0,
        report_timeout_s=2.0,
        _env_file=None,
    )


@pytest.fixture
def settings_without_key() -> Settings:
    return Settings(deepseek_api_key=None, _env_file=None)


def make_fake_llm(responses: list[str], *, delay_s: float = 0.0) -> RunnableLambda:
    """按序返回预设内容的异步 Fake Runnable。

    用 ``RunnableLambda`` 而不是普通类：LCEL 需要 Runnable/callable/dict，
    普通类实例会抛 ``TypeError: Expected a Runnable, callable or dict``。
    """
    items = iter(responses)

    async def _call(_messages: Any) -> AIMessage:
        if delay_s:
            await asyncio.sleep(delay_s)
        try:
            content = next(items)
        except StopIteration:
            content = "{}"
        return AIMessage(content=content)

    return RunnableLambda(_call).with_config(run_name="fake_llm")


def make_recording_fake_llm(
    responses: list[str], *, delay_s: float = 0.0
) -> tuple[RunnableLambda, list[int]]:
    """返回 ``(fake, calls)``；``calls`` 的长度即调用次数。"""
    items = iter(responses)
    calls: list[int] = []

    async def _call(_messages: Any) -> AIMessage:
        calls.append(1)
        if delay_s:
            await asyncio.sleep(delay_s)
        try:
            content = next(items)
        except StopIteration:
            content = "{}"
        return AIMessage(content=content)

    return RunnableLambda(_call).with_config(run_name="fake_llm"), calls


@asynccontextmanager
async def running_lifespan(app: FastAPI) -> AsyncIterator[None]:
    """手动驱动 lifespan。

    裸 ``ASGITransport`` 不会触发 startup/shutdown（实测 lifespan 事件为空），
    直接用它会写出「永远通过」的假绿测试。
    """
    ctx = app.router.lifespan_context(app)
    await ctx.__aenter__()
    try:
        yield
    finally:
        await ctx.__aexit__(None, None, None)


def build_test_app(
    settings: Settings,
    *,
    example_responses: list[str] | None = None,
    explain_responses: list[str] | None = None,
    analyze_responses: list[str] | None = None,
    compose_responses: list[str] | None = None,
    delay_s: float = 0.0,
    client: httpx.AsyncClient | None = None,
):
    """构造被测应用并注入 FakeLLM（完全不碰网络）。

    做法：先正常 ``create_app``（拿到真实 chain 形状），再把 ``state`` 里的
    Runnable 换成 Fake——prompt 与解析仍是真实对象。
    """
    from app.chains.example_chain import build_example_chain
    from app.chains.explain_chain import build_explain_chain
    from app.chains.report_graph import build_report_graph
    from app.main import create_app

    app = create_app(settings=settings, http_client=client)

    async def _startup_hook() -> None:
        pass

    original_lifespan = app.router.lifespan_context

    @asynccontextmanager
    async def _lifespan(application: FastAPI):
        async with original_lifespan(application) as state:
            if example_responses is not None:
                application.state.example_chain = build_example_chain(
                    make_fake_llm(example_responses, delay_s=delay_s)
                )
            if explain_responses is not None:
                application.state.explain_chain = build_explain_chain(
                    make_fake_llm(explain_responses, delay_s=delay_s)
                )
            if analyze_responses is not None and compose_responses is not None:
                analyze_llm, analyze_calls = make_recording_fake_llm(
                    analyze_responses, delay_s=delay_s
                )
                compose_llm, compose_calls = make_recording_fake_llm(
                    compose_responses, delay_s=delay_s
                )
                application.state.report_graph_factory = lambda stats: build_report_graph(
                    stats, analyze_llm, compose_llm
                )
                application.state.report_calls = (analyze_calls, compose_calls)
            yield state

    app.router.lifespan_context = _lifespan
    return app
