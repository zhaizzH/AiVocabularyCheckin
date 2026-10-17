"""错题讲解链（单步 LCEL）。

与例句链同形态。注意提示词**不得**假设 ``wrongContext`` 里含用户误选项
（实测该字段形如「该词在复习中答错，累计错误 3 次」）。
"""

from __future__ import annotations

from langchain_core.output_parsers import StrOutputParser
from langchain_core.runnables import Runnable

from app.chains.parsing import converge_fields, parse_json_object, require_non_empty
from app.chains.prompts import explain_prompt
from app.schemas.explain import ExplainRequest, ExplainResponse

_RESPONSE_FIELDS = ("explanation", "advice")


def build_explain_chain(json_llm: Runnable) -> Runnable:
    return explain_prompt | json_llm | StrOutputParser()


async def run_explain(chain: Runnable, request: ExplainRequest) -> ExplainResponse:
    raw = await chain.ainvoke(
        {
            "word": request.word,
            "translation": request.translation,
            "wrong_context": request.wrongContext or "（无额外上下文）",
        }
    )
    payload = parse_json_object(raw)
    converged = converge_fields(payload, _RESPONSE_FIELDS)
    require_non_empty(converged, "explanation", "advice")
    return ExplainResponse(**converged)
