from typing import Literal, cast

from pydantic import BaseModel, Field, PositiveInt

from app.core.contracts import ChatDefaultsContract, get_contracts
from app.services.reasoning import EffortChoice, Scenario

# ge/le 校验边界须在类定义期取常量，故在装配期读取契约（lru_cache 缓存，副作用止于一次文件读取）
_GEO: ChatDefaultsContract = get_contracts().chat_defaults


def _effort_default() -> EffortChoice:
    # 实例化时取契约默认值，避免 import 期副作用（对齐 schemas/vision.py 约定）
    return cast(EffortChoice, get_contracts().chat_defaults.effort_default)


def _scenario_default() -> Scenario:
    return cast(Scenario, get_contracts().chat_defaults.scenario_default)


class ChatMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1)


class HealthProfilePayload(BaseModel):
    id: PositiveInt
    display_name: str = Field(min_length=1, max_length=50)
    gender: str = Field(min_length=1, max_length=10)
    birth_date: str
    relationship: str = Field(min_length=1, max_length=20)
    allergies: list[str] = Field(default_factory=list, max_length=30)


class AgentChatRequest(BaseModel):
    """Agent 对话入参：消息历史由 server-java 组装（会话持久化在它那边）。"""

    messages: list[ChatMessage] = Field(min_length=1)
    patient_id: PositiveInt
    conversation_id: PositiveInt
    health_profile: HealthProfilePayload | None = None
    effort: EffortChoice = Field(default_factory=_effort_default)
    scenario: Scenario = Field(default_factory=_scenario_default)
    # 知识增强源（ADR-0010）：rag/graph/none；缺省时 server-py 按 scenario 默认
    knowledge_source: str | None = None
    # 用户授权定位后的经纬度；拒绝授权时不传，按位置的服务端能力据此省略坐标
    longitude: float | None = Field(default=None, ge=_GEO.longitude_min, le=_GEO.longitude_max)
    latitude: float | None = Field(default=None, ge=_GEO.latitude_min, le=_GEO.latitude_max)
    # 号源卡重试：复用已确定的标准科室 ID 直查，跳过科室解析与 Agent 回复。
    # 字段名与 contracts/guided-registration.json 的 retry_request_field 一致（契约钉值测试钉死）。
    retry_standard_department_id: int | None = None
    # 预问诊草稿标识：server-java 校验归属/状态后强制 preconsultation 场景。
    # 透传给编排层供异步摘要 task 回调 server-java 落草稿（摘要不再阻塞 message 事件）。
    preconsultation_draft_id: int | None = None
    # 票 80 处方药多处方选择卡点选回传：用户在处方选择卡点选某处方后，server-java
    # 透传所选 prescription_id（归属校验延后到 prepare_drug_order），编排层注入
    # AgentContext.selected_prescription_id，由 SystemMessage 提示模型直接装配确认卡。
    prescription_id: int | None = None
