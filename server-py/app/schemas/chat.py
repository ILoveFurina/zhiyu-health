from typing import Literal

from pydantic import BaseModel, Field, PositiveInt

from app.services.reasoning import EffortChoice, Scenario


class ChatMessage(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1)


class AgentChatRequest(BaseModel):
    """Agent 对话入参：消息历史由 server-java 组装（会话持久化在它那边）。"""

    messages: list[ChatMessage] = Field(min_length=1)
    patient_id: PositiveInt
    conversation_id: PositiveInt
    effort: EffortChoice = "auto"
    scenario: Scenario = "triage"
