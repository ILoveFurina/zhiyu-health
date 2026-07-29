"""报告解读结构化契约。"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

DISCLAIMER = "仅供参考，不替代医生诊断"


class ReportItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    value: str = Field(min_length=1)
    reference_range: str = Field(min_length=1)
    unit: str
    priority: Literal["red", "yellow", "blue", "green"]
    explanation: str = Field(min_length=1)
    action: str = Field(min_length=1)
    page: int = Field(ge=1)


class ReportInterpretation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    summary: str = Field(min_length=1)
    items: list[ReportItem]
    actions: list[str]
    unreadable: list[str]
    # 仅供 server-py 拒绝超范围材料，API 卡片不暴露内部分类字段。
    scope_supported: bool = Field(exclude=True)


class VisionResponse(BaseModel):
    result: ReportInterpretation
    disclaimer: str = DISCLAIMER
    page_count: int = Field(ge=1)
