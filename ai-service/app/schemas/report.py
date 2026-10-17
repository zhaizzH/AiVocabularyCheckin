"""学习报告（契约 §9.3）。"""

from __future__ import annotations

from pydantic import BaseModel, Field

from app.schemas.common import StudyStats


class ReportRequest(BaseModel):
    studyStats: StudyStats


class ReportResponse(BaseModel):
    """响应字段与契约 §9.3 逐字一致。三段均必须非空。"""

    overview: str = Field(default="")
    weakness: str = Field(default="")
    suggestion: str = Field(default="")


class ReportAnalysis(BaseModel):
    """``analyze`` 节点的**内部**结构，不进入 HTTP 契约。

    单独建模而不直接用 ``dict`` 的理由：
    ①``compose`` 拿到的必须是「已经校验过、字段确定存在」的分析，
      否则一个键名笔误就会让提示词里出现 ``None`` 并生成无依据的报告；
    ②把它与 ``ReportResponse`` 分开，避免有人误以为这些字段对外可见。
    """

    strengths: list[str] = Field(default_factory=list)
    weaknesses: list[str] = Field(default_factory=list)
    focus: str = Field(default="")
