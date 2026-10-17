"""错题讲解（契约 §9.2）。"""

from __future__ import annotations

from pydantic import BaseModel, Field, field_validator


class ExplainRequest(BaseModel):
    word: str = Field(min_length=1, description="答错的单词")
    translation: str = Field(min_length=1, description="单词中文释义")
    wrongContext: str = Field(
        default="",
        description=(
            "答错上下文。实测形如「该词在复习中答错，累计错误 3 次」，"
            "不含用户所选的错误选项，提示词不得假设能拿到它。"
        ),
    )

    @field_validator("word", "translation")
    @classmethod
    def _not_blank(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("must not be blank")
        return stripped

    @field_validator("wrongContext")
    @classmethod
    def _strip_context(cls, value: str) -> str:
        return value.strip()


class ExplainResponse(BaseModel):
    """响应字段与契约 §9.2 逐字一致。"""

    explanation: str = Field(default="")
    advice: str = Field(default="")
