"""共用 Pydantic 模型。

字段名必须与契约 §9 逐字一致，**不得**改名或增删。
"""

from __future__ import annotations

from pydantic import BaseModel, Field


class StudyStats(BaseModel):
    """学习统计（契约 §9.3 的 ``studyStats``）。

    全部字段由 backend 计算后传入，ai-service 不做任何统计计算（红线：不查库）。
    """

    studyDays: int = Field(description="累计学习天数")
    totalLearned: int = Field(description="累计已学单词数")
    checkInRate: int = Field(description="近两周打卡率（百分比，0-100）")
    topMistakeWords: list[str] = Field(
        default_factory=list,
        description="错误次数最多的词，可能为空数组（用户无错题）",
    )
    masteryRate: int = Field(description="掌握度（百分比，0-100；无错题时为 100）")
