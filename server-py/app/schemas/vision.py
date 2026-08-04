"""视觉场景结构化契约。

报告解读（REPORT）与拍照分析（SKIN/DIET）共用同一 scenario 驱动管道（票 15）：
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


class DietFood(BaseModel):
    """饮食分析识别的单项食材/菜品。

    risk_level 与报告 priority 解耦：饮食场景不做营养诊断，risk_level 只表达"日常可食 /
    注意限量 / 过敏风险"的轻重度提示，red 仅表示命中过敏原或明显不适宜，不表示急救
    （硬约束 1/2）。
    """

    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    # 估算摄入量（如"约 200g""一碗"），看不清时不得猜测，可为"无法估量"
    estimated_amount: str = Field(min_length=1)
    risk_level: Literal["green", "yellow", "red"]
    explanation: str = Field(min_length=1)


class DietAnalysis(BaseModel):
    """拍照饮食分析结果模型（票 16，照搬 15 皮肤模板的第二个拍照分析场景）。

    差异化点（见票 16）：结合健康档案过敏史给出个性化一句提醒。档案注入由 interpreter
    的 _content_blocks 统一完成（scenario 无关），饮食 prompt 收到过敏史后在识别出食材
    后比对过敏原，命中则把对应 DietFood.risk_level 置 red 并在 personal_tip 产出风险提示。
    无激活档案时正常分析，仅缺个性化提醒句。scope_supported 为 false 时表示照片不属于
    饮食场景（如医学影像/皮肤/舌苔照片），由饮食场景策略拒绝。
    """

    model_config = ConfigDict(extra="forbid")

    meal_type: str = Field(min_length=1)
    foods: list[DietFood]
    # 估算总热量（千卡）；看不清时可为"无法估量"
    estimated_calories: str = Field(min_length=1)
    nutrition_summary: str = Field(min_length=1)
    diet_advice: str = Field(min_length=1)
    # 个性化一句提醒：命中过敏原时为风险提示，否则为年龄/性别通用话术；无档案时为空串
    personal_tip: str = ""
    need_doctor: bool
    scope_supported: bool = Field(exclude=True)


class TongueAnalysis(BaseModel):
    """拍舌苔中医辨证结果模型（票 17，照搬 15/16 拍照模板的第三个场景）。

    差异化合规边界（ADR-0024）：调理建议只讲方向，不出药材/方剂/剂量。constitution 为
    体质辨识结论，care_direction 给作息/运动/饮食原则/通用食材（如山药红枣等日常食物）
    类调理方向，禁止出现纯药材（如黄芩/附子）、方剂名或剂量。这是中医辨证场景能落在
    硬约束 2 与 ADR-0016"通用知识解释"白名单内的前提，不触碰"个性化用药决策"红线。
    舌象急症（如镜面舌/霉酱苔）只做软兜底：need_doctor 为 true 时引导"建议尽快就医"，
    不回流到 RedFlagRuleEngine 确定性规则通道（与 15 拍皮肤软兜底先例同构）。
    scope_supported 为 false 时表示照片不属于舌苔场景（如医学影像/皮肤/饮食照片），
    由舌苔场景策略拒绝。
    """

    model_config = ConfigDict(extra="forbid")

    # 体质辨识结论（如"气虚质""湿热质"），不含药材/方剂/剂量
    constitution: str = Field(min_length=1)
    # 舌象特征摘要（如舌质/舌苔颜色形态），看不清时不得猜测
    tongue_features: str = Field(min_length=1)
    # 调理方向：作息/运动/饮食原则/通用食材等日常食物方向，禁止药材/方剂/剂量（ADR-0024）
    care_direction: str = Field(min_length=1)
    # 通用饮食原则提示（如"少食生冷""饮食有节"），不含个性化用药
    diet_principle: str = Field(min_length=1)
    # 急症软兜底话术：舌象指向重病特征（如镜面舌/霉酱苔）时为"建议尽快就医"，否则为空串
    urgency_hint: str = ""
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
