"""会话与消息（领域术语见 CONTEXT.md："会话"，首条消息发出时惰性创建）。"""

from datetime import datetime

from sqlalchemy import ForeignKey, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base

# 消息角色与类型取值（外部契约，勿改字符串本身）
ROLE_USER = "user"
ROLE_ASSISTANT = "assistant"
KIND_TEXT = "text"
KIND_RED_FLAG = "red_flag"


class Conversation(Base):
    __tablename__ = "conversations"

    id: Mapped[int] = mapped_column(primary_key=True)
    patient_id: Mapped[int] = mapped_column(ForeignKey("patients.id"), index=True)
    title: Mapped[str] = mapped_column(String(50))
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())
    last_active_at: Mapped[datetime] = mapped_column(server_default=func.now())


class Message(Base):
    __tablename__ = "messages"

    id: Mapped[int] = mapped_column(primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    role: Mapped[str] = mapped_column(String(20))  # ROLE_USER / ROLE_ASSISTANT
    kind: Mapped[str] = mapped_column(String(20), default=KIND_TEXT)  # KIND_TEXT / KIND_RED_FLAG
    content: Mapped[str] = mapped_column(Text)
    effort: Mapped[str | None] = mapped_column(String(10), nullable=True)
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())
