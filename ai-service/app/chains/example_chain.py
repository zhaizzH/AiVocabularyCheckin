"""例句生成链（单步 LCEL）。

形态：``prompt | json_llm | StrOutputParser()`` → JSON 解析 → 字段收敛 → Response。

为什么不用 langgraph：这一步是「一次模型调用 + 一次解析」，
用 ``StateGraph`` 包一层只有 1 节点 1 边，是仪式性复杂度。
S3 §3.5 要求的是「多步 Chain」，不是「所有东西都上图」。
"""

from __future__ import annotations

from langchain_core.output_parsers import StrOutputParser
from langchain_core.runnables import Runnable

from app.chains.parsing import converge_fields, parse_json_object, require_non_empty
from app.chains.prompts import example_prompt
from app.schemas.example import ExampleRequest, ExampleResponse

_RESPONSE_FIELDS = ("example", "exampleTranslation", "mnemonic")


def build_example_chain(json_llm: Runnable) -> Runnable:
    """构造例句链。``json_llm`` 由调用方按当前 lifespan 的 HTTP client 注入。"""
    return example_prompt | json_llm | StrOutputParser()


async def run_example(chain: Runnable, request: ExampleRequest) -> ExampleResponse:
    """执行例句生成并对结果做严格收敛。"""
    raw = await chain.ainvoke(
        {
            "word": request.word,
            "translation": request.translation,
            # context 可能为空串（backend 的 resolveCategory 会返回 ""）。
            "context": request.context or "（未指定）",
        }
    )
    payload = parse_json_object(raw)
    converged = converge_fields(payload, _RESPONSE_FIELDS)
    # 空例句是「无意义的成功」：backend 的空值兜底只在收到失败时触发，
    # 收到空串会被记成成功，用户看到一片空白。故必须由我们判失败。
    require_non_empty(converged, "example", "exampleTranslation")
    return ExampleResponse(**converged)
