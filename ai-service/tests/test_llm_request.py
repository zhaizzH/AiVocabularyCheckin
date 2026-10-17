"""锁版 SDK 的最终 HTTP 请求体（离线，不访问外网）。

只在模型实例属性上断言是不够的：真正决定线上成败的是 OpenAI SDK
**最终序列化出的 JSON body**。这里用 ``httpx.MockTransport`` 拦截，
断言两件事同时成立：

1. 顶层 ``response_format == {"type": "json_object"}`；
2. 顶层 ``thinking == {"type": "disabled"}``（不是 ``extra_body`` 字面包装层）。

并额外断言 ``max_tokens`` 是供应商认识的键——``langchain-openai 1.7.2``
会把构造参数 ``max_tokens`` 改名成 ``max_completion_tokens``，DeepSeek 不认，
所以实现必须走 ``extra_body``。
"""

from __future__ import annotations

import json

import httpx

from app.chains.example_chain import build_example_chain, run_example
from app.chains.prompts import report_analyze_prompt, report_compose_prompt
from app.core.llm import (
    MAX_TOKENS_EXAMPLE,
    MAX_TOKENS_REPORT_ANALYZE,
    MAX_TOKENS_REPORT_COMPOSE,
    build_json_runnable,
)
from app.schemas.example import ExampleRequest

CANNED_RESPONSE = {
    "id": "chatcmpl-test",
    "object": "chat.completion",
    "created": 1,
    "model": "deepseek-flash",
    "choices": [
        {
            "index": 0,
            "message": {
                "role": "assistant",
                "content": '{"example": "He abandoned it.", '
                '"exampleTranslation": "他放弃了。", "mnemonic": "a+band+on"}',
            },
            "finish_reason": "stop",
        }
    ],
    "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
}


def make_capturing_client(captured: list[dict]) -> httpx.AsyncClient:
    async def handler(request: httpx.Request) -> httpx.Response:
        captured.append(json.loads(request.content.decode("utf-8")))
        return httpx.Response(200, json=CANNED_RESPONSE)

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_example_request_body_has_json_mode_and_thinking_disabled(settings):
    captured: list[dict] = []
    client = make_capturing_client(captured)
    try:
        llm = build_json_runnable(
            settings, http_async_client=client, max_tokens=MAX_TOKENS_EXAMPLE
        )
        chain = build_example_chain(llm)
        await run_example(
            chain, ExampleRequest(word="abandon", translation="v. 放弃", context="CET4")
        )
    finally:
        await client.aclose()

    assert len(captured) == 1
    body = captured[0]
    # ① JSON 输出模式在顶层
    assert body["response_format"] == {"type": "json_object"}
    # ② thinking 在顶层（SDK 把 ChatOpenAI 的 extra_body 合并进 body 根部）
    assert body["thinking"] == {"type": "disabled"}
    assert "extra_body" not in body, "extra_body 不应作为字面包装层出现在请求体里"
    # ③ max_tokens 必须是供应商认识的键
    assert body["max_tokens"] == MAX_TOKENS_EXAMPLE
    assert "max_completion_tokens" not in body, (
        "langchain-openai 把 max_tokens 改名成 max_completion_tokens，DeepSeek 不认；"
        "必须经 extra_body 传"
    )
    # ④ 不传 temperature（thinking 模式不支持，避免假象）
    assert "temperature" not in body


async def test_report_nodes_also_send_json_mode_and_thinking_disabled(settings):
    """report 的 analyze 与 compose 两个节点必须同样满足请求体约束。"""
    captured: list[dict] = []
    client = make_capturing_client(captured)
    try:
        analyze_llm = build_json_runnable(
            settings, http_async_client=client, max_tokens=MAX_TOKENS_REPORT_ANALYZE
        )
        compose_llm = build_json_runnable(
            settings, http_async_client=client, max_tokens=MAX_TOKENS_REPORT_COMPOSE
        )
        await (report_analyze_prompt | analyze_llm).ainvoke(
            {
                "study_days": 12,
                "total_learned": 346,
                "check_in_rate": 85,
                "mastery_rate": 58,
                "top_mistake_words": "abandon",
            }
        )
        await (report_compose_prompt | compose_llm).ainvoke(
            {
                "study_days": 12,
                "total_learned": 346,
                "check_in_rate": 85,
                "mastery_rate": 58,
                "top_mistake_words": "abandon",
                "analysis": '{"focus": "复习错题"}',
            }
        )
    finally:
        await client.aclose()

    assert len(captured) == 2
    assert captured[0]["max_tokens"] == MAX_TOKENS_REPORT_ANALYZE
    assert captured[1]["max_tokens"] == MAX_TOKENS_REPORT_COMPOSE
    for body in captured:
        assert body["response_format"] == {"type": "json_object"}
        assert body["thinking"] == {"type": "disabled"}
        assert "max_completion_tokens" not in body


async def test_prompt_contains_json_keyword_and_example(settings):
    """DeepSeek JSON mode 硬性要求：提示词含 "json" 字样 + JSON 示例。"""
    from app.chains.prompts import example_prompt, explain_prompt

    for prompt in (example_prompt, explain_prompt, report_analyze_prompt, report_compose_prompt):
        context = {name: "x" for name in prompt.input_variables}
        text = " ".join(str(message.content) for message in prompt.format_messages(**context))
        lowered = text.lower()
        assert "json" in lowered, f"提示词缺少 json 字样: {lowered[:120]}"
        assert "{" in text and "}" in text, "提示词缺少 JSON 结构示例"
