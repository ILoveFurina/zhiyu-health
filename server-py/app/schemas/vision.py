"""视觉场景结构化契约。

报告解读（REPORT）与拍照分析（SKIN）共用同一 scenario 驱动管道（票 15）：
每个场景在 scenarios.py 注册自己的 system_prompt 与 result_model，interpreter 按
policy.result_model 动态校验，document 按场景分发预处理。各 result_model 字段互不
耦合，新增场景只追加模型与策略，不改动既有 REPORT 结构。
"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_serializer

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


class SkinFinding(BaseModel):
    """皮肤分析单项发现：肤质/常见皮肤问题与对应护理建议。

    severity 与报告 priority 解耦：皮肤场景不做疾病诊断，severity 只表达"日常护理 /
    建议就医"的轻重度提示，red 仅表示建议尽快面诊皮肤科，不表示急救（硬约束 1/2）。
    """

    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    severity: Literal["green", "yellow", "red"]
    explanation: str = Field(min_length=1)
    care_advice: str = Field(min_length=1)


class SkinAnalysis(BaseModel):
    """拍照皮肤分析结果模型（票 15 首个拍照分析场景）。

    与 ReportInterpretation 平级：scope_supported 为 false 时表示照片不属于可分析
    皮肤照片（如拍到了医学影像/文档），由皮肤场景策略拒绝；为 true 时正常返回。
    """

    model_config = ConfigDict(extra="forbid")

    skin_type: str = Field(min_length=1)
    findings: list[SkinFinding]
    care_summary: str = Field(min_length=1)
    need_doctor: bool
    scope_supported: bool = Field(exclude=True)


class VisionResponse(BaseModel):
    """视觉分析统一出口：result 为分场景的结构化结果，page_count 为预处理页数。

    泛化后 result 不再写死 ReportInterpretation，而是任意已注册场景的 result_model
    实例；调用方（server-java）按 scenario 已知结构消费，不在此处做联合类型校验。
    """

    result: BaseModel
    # 文案以跨栈契约为唯一事实源；default_factory 在实例化时取值，避免 import 期副作用。
    disclaimer: str = Field(default_factory=lambda: get_contracts().disclaimer.text)
    page_count: int = Field(ge=1)

    @field_serializer("result")
    def _serialize_result(self, value: BaseModel) -> dict[str, object]:
        # pydantic v2 按 *声明* 类型（BaseModel）序列化字段，会丢弃运行时子类的字段，
        # 只 dump 出空 dict。这里改用实例自身的 model_dump() 保留真实子类结构，
        # 使 report/皮肤等各场景 result_model 的字段完整透传给 server-java。
        return value.model_dump()
