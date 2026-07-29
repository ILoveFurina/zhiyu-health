"""报告解读结构化契约。"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.core.contracts import get_contracts


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
    # 文案以跨栈契约为唯一事实源；default_factory 在实例化时取值，避免 import 期副作用。
    disclaimer: str = Field(default_factory=lambda: get_contracts().disclaimer.text)
    page_count: int = Field(ge=1)
