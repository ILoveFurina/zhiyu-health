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


class AgentChatRequest(BaseModel):
    """Agent 对话入参：消息历史由 server-java 组装（会话持久化在它那边）。"""

    messages: list[ChatMessage] = Field(min_length=1)
    patient_id: PositiveInt
    conversation_id: PositiveInt
    effort: EffortChoice = Field(default_factory=_effort_default)
    scenario: Scenario = Field(default_factory=_scenario_default)
    # 用户授权定位后的经纬度；拒绝授权时不传，find_hospitals 据此降级
    longitude: float | None = Field(default=None, ge=_GEO.longitude_min, le=_GEO.longitude_max)
    latitude: float | None = Field(default=None, ge=_GEO.latitude_min, le=_GEO.latitude_max)
