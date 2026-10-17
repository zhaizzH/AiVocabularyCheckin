"""例句生成（契约 §9.1）。"""

from __future__ import annotations

from pydantic import BaseModel, Field, field_validator


class ExampleRequest(BaseModel):
    word: str = Field(min_length=1, description="要造句的单词")
    translation: str = Field(min_length=1, description="单词中文释义")
    context: str = Field(
        default="",
        description="词书分类（CET4/CET6/KAOYAN），可能为空串",
    )

    @field_validator("word", "translation")
    @classmethod
    def _not_blank(cls, value: str) -> str:
        """空白词无法造句，属于「确实无法生成」的输入，提前拒绝。"""
        stripped = value.strip()
        if not stripped:
            raise ValueError("must not be blank")
        return stripped

    @field_validator("context")
    @classmethod
    def _strip_context(cls, value: str) -> str:
        """context 允许为空串（backend 的 resolveCategory 可能返回 ""）。"""
        return value.strip()


class ExampleResponse(BaseModel):
    """响应字段与契约 §9.1 逐字一致。"""

    example: str = Field(default="")
    exampleTranslation: str = Field(default="")
    mnemonic: str = Field(default="")
