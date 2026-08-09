"""对话主链路共享概念。

这些值由 server-java 的可信请求上下文产生，供对话编排、LangGraph 和业务工具共同
使用。它们不负责 HTTP 解析、模型装配或事件投影；尤其是患者、会话和定位信息绝不
作为 LLM 自由生成的工具参数。
"""

from dataclasses import dataclass
from typing import Any, Literal


@dataclass(frozen=True)
class HealthProfileContext:
    id: int
    display_name: str
    gender: str
    birth_date: str
    relationship: str
    allergies: list[str]


@dataclass(frozen=True)
class AgentContext:
    """模型运行时可读取、但用户文本无法覆盖的可信业务上下文。"""

    patient_id: int
    conversation_id: int
    health_profile: HealthProfileContext | None = None
    # 定位只供确定性编排和工具使用，不进入 system prompt，避免模型誊抄坐标。
    longitude: float | None = None
    latitude: float | None = None
    # rag/graph 决定注入哪个只读知识工具；none 表示允许裸 LLM 降级。
    knowledge_source: str = "none"
    # 场景决定提示词和业务工具集合，不作为模型可见的自由文本。
    scenario: str = "triage"
    # 票 80：处方药多处方选择卡点选回传的所选处方 ID（可信注入，不暴露给模型可见参数）。
    # 非空时由 runner._to_lc_messages 注入一条 SystemMessage 提示模型直接调用
    # prepare_drug_order(prescription_id=<该值>)，避免模型誊抄 id 出错或重复查处方。
    selected_prescription_id: int | None = None
    # 明确挂号/导诊请求可约束首个模型调用使用科室工具；工具返回后 runner 自动解除。
    department_tool_choice: str | None = None
    # server-java 可信标准科室目录，供模型选名与工具按 id 解析，避免重复查询和编造名称。
    standard_departments: tuple[tuple[int, str], ...] = ()


# Literal 无法从 JSON 动态生成；契约一致性由 test_contract_consumption.py 钉死。
CardEvent = Literal[
    "doctor_recommendations",
    "doctor_slots",
    "hospital_recommendations",
    "appointment",
    "appointments",
    "department_slots",
    "department_options",
    # 票 79/78/79/80：购药相关卡片事件（复用契约已登记 kind，不新增）
    "medications",
    "prescriptions",
    "drug_order_prepare",
    "drug_order_confirm",
    "drug_order",
]
TraceEvent = Literal["tool_start", "tool_end"]
TraceResult = Literal["success", "error", "skipped"]


@dataclass(frozen=True)
class AgentOutput:
    """runner 的稳定输出：正文增量、思考、知识状态、工具 trace 或业务卡片。"""

    event: Literal["token", "thinking", "knowledge"] | CardEvent | TraceEvent
    data: str | dict[str, Any]
