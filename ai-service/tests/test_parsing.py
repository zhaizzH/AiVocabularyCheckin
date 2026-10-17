"""解析容错与字段收敛（纯单元测试，不涉及 LLM）。"""

from __future__ import annotations

import pytest
from fastapi import HTTPException

from app.chains.parsing import converge_fields, parse_json_object, require_non_empty


def test_plain_json():
    assert parse_json_object('{"a": 1}') == {"a": 1}


def test_markdown_fenced_json():
    assert parse_json_object('```json\n{"a": 2}\n```') == {"a": 2}
    assert parse_json_object('```\n{"a": 3}\n```') == {"a": 3}


def test_json_with_surrounding_text():
    assert parse_json_object('好的，结果如下：{"a": 4} 希望有帮助') == {"a": 4}


def test_braces_inside_string_are_not_treated_as_structure():
    parsed = parse_json_object('{"a": "brace } inside", "b": 2}')
    assert parsed == {"a": "brace } inside", "b": 2}


def test_nested_object():
    assert parse_json_object('前言 {"a": {"b": [1, 2]}, "c": "x"} 后语') == {
        "a": {"b": [1, 2]},
        "c": "x",
    }


@pytest.mark.parametrize(
    "text",
    ["", "   ", '{"a": 1', "[1, 2]", "no json here", "null", '"just a string"'],
)
def test_unparseable_raises_502(text):
    with pytest.raises(HTTPException) as excinfo:
        parse_json_object(text)
    assert excinfo.value.status_code == 502
    assert excinfo.value.detail == "ai_service_bad_model_output"


def test_converge_fills_missing_and_coerces_types():
    converged = converge_fields(
        {"example": " hi ", "exampleTranslation": 123, "mnemonic": None},
        ("example", "exampleTranslation", "mnemonic"),
    )
    assert converged == {"example": "hi", "exampleTranslation": "123", "mnemonic": ""}


def test_converge_drops_structured_values():
    converged = converge_fields({"example": {"nested": 1}}, ("example",))
    assert converged == {"example": ""}


def test_require_non_empty_raises_on_blank():
    with pytest.raises(HTTPException) as excinfo:
        require_non_empty({"example": "   "}, "example")
    assert excinfo.value.status_code == 502
    assert excinfo.value.detail == "ai_service_empty_result"


def test_require_non_empty_passes():
    require_non_empty({"example": "ok"}, "example")
