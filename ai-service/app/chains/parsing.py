"""模型输出解析与字段收敛。

模型并不总是乖乖返回纯 JSON。实测常见形态：纯 JSON、````` ```json ... ````` 围栏、
前后带解释文字。策略按顺序尝试：

1. ``json.loads`` 直接成功；
2. 剥离 Markdown 代码围栏后重试；
3. 括号配平扫描，抽取**第一个完整的 ``{...}`` 对象**后重试；
4. 全部失败 → 抛 502 ``ai_service_bad_model_output``。

**刻意不做**的容错：
- 不用正则从文本里猜字段（会把错的东西猜成对的，错误被掩盖到下游）；
- 不用 LLM 二次修复（多一次调用、多一次失败点，且不可预测）；
- 不返回半截文本（prd 明确要求解析失败就抛异常）。
"""

from __future__ import annotations

import json
from typing import Any

from app.core.errors import bad_output_error, empty_result_error

# 可能的 Markdown 围栏前缀/后缀。
_FENCE_PREFIXES = ("```json", "```JSON", "```")
_FENCE_SUFFIX = "```"


def _strip_code_fence(text: str) -> str | None:
    """剥离 Markdown 代码围栏；不含围栏时返回 ``None``。"""
    stripped = text.strip()
    if not stripped.startswith("```"):
        return None

    body = stripped
    for prefix in _FENCE_PREFIXES:
        if body.startswith(prefix):
            body = body[len(prefix) :]
            break
    if body.rstrip().endswith(_FENCE_SUFFIX):
        body = body.rstrip()[: -len(_FENCE_SUFFIX)]
    return body.strip()


def _extract_first_object(text: str) -> str | None:
    """括号配平扫描，返回第一个完整的 ``{...}`` 片段。

    跳过字符串字面量内部的括号（否则 ``{"a": "}"}`` 会被提前截断）。
    """
    start = text.find("{")
    if start == -1:
        return None

    depth = 0
    in_string = False
    escaped = False
    for index in range(start, len(text)):
        char = text[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue

        if char == '"':
            in_string = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start : index + 1]
    # 未闭合
    return None


def parse_json_object(text: str) -> dict[str, Any]:
    """把模型输出解析成 dict；失败抛 502。"""
    if text is None or not text.strip():
        raise bad_output_error()

    candidates: list[str] = [text]

    defenced = _strip_code_fence(text)
    if defenced is not None:
        candidates.append(defenced)

    extracted = _extract_first_object(defenced if defenced is not None else text)
    if extracted is not None:
        candidates.append(extracted)

    for candidate in candidates:
        try:
            parsed = json.loads(candidate)
        except (json.JSONDecodeError, ValueError):
            continue
        if isinstance(parsed, dict):
            return parsed
        # 顶层是数组/标量：不符合契约的 JSON 对象要求，继续尝试下一个候选。
        # 若全部候选都不是 dict，最终抛 502。

    raise bad_output_error()


def converge_fields(
    payload: dict[str, Any], allowed: tuple[str, ...]
) -> dict[str, str]:
    """字段收敛：白名单 + 缺失补空串 + 非字符串转 ``str()`` + strip。

    模型偶尔会把字符串字段返回成数字、数组或 ``null``。这里统一成字符串，
    宁可给出 ``"123"`` 也不要因为类型不符把整次成功变成 502。
    """
    converged: dict[str, str] = {}
    for name in allowed:
        value = payload.get(name)
        if value is None:
            converged[name] = ""
        elif isinstance(value, str):
            converged[name] = value.strip()
        elif isinstance(value, (dict, list)):
            # 结构化值无法安全地当成正文，交给下游的空值校验判失败。
            converged[name] = ""
        else:
            converged[name] = str(value).strip()
    return converged


def require_non_empty(converged: dict[str, str], *names: str) -> None:
    """关键字段必须非空，否则抛 502。

    空成功对用户没有意义：backend 的 ``example`` 降级兜底只在**收到失败**时触发，
    收到空串会被记成成功，用户看到一片空白。
    """
    for name in names:
        if not converged.get(name, "").strip():
            raise empty_result_error()
