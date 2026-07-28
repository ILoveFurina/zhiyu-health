"""会话与消息持久化（业务逻辑层；路由只调 service）。

会话惰性创建：首条用户消息发出时才建会话，标题取首条用户消息截断。
"""

from sqlalchemy import func, select
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session

from app.models import Conversation, Message

TITLE_MAX_LENGTH = 20
CONTEXT_MESSAGE_LIMIT = 20


class ConversationNotFoundError(LookupError):
    """会话不存在或不属于当前患者（不区分以免泄露存在性）。"""


class ConversationService:
    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def get_or_create_for_patient(
        self, patient_id: int, conversation_id: int | None, first_text: str
    ) -> Conversation:
        with Session(self._engine) as session:
            if conversation_id is not None:
                conversation = session.get(Conversation, conversation_id)
                if conversation is None or conversation.patient_id != patient_id:
                    raise ConversationNotFoundError(f"会话 {conversation_id} 不存在")
                return conversation
            conversation = Conversation(
                patient_id=patient_id,
                title=first_text[:TITLE_MAX_LENGTH],
            )
            session.add(conversation)
            session.commit()
            session.refresh(conversation)
            return conversation

    def get_for_patient(self, conversation_id: int, patient_id: int) -> Conversation | None:
        with Session(self._engine) as session:
            conversation = session.get(Conversation, conversation_id)
            if conversation is None or conversation.patient_id != patient_id:
                return None
            return conversation

    def append_message(
        self,
        conversation_id: int,
        role: str,
        content: str,
        kind: str = "text",
        effort: str | None = None,
    ) -> Message:
        with Session(self._engine) as session:
            message = Message(
                conversation_id=conversation_id,
                role=role,
                kind=kind,
                content=content,
                effort=effort,
            )
            session.add(message)
            conversation = session.get(Conversation, conversation_id)
            if conversation is not None:
                # 活跃时间随新消息推进（票 27 对话记录按最近活跃倒序依赖此字段）
                conversation.last_active_at = func.now()
            session.commit()
            session.refresh(message)
            return message

    def list_messages(self, conversation_id: int) -> list[Message]:
        with Session(self._engine) as session:
            return list(
                session.scalars(
                    select(Message)
                    .where(Message.conversation_id == conversation_id)
                    .order_by(Message.id)
                )
            )

    def recent_context(self, conversation_id: int, limit: int = CONTEXT_MESSAGE_LIMIT) -> list[dict[str, str]]:
        """取最近 N 条消息作为多轮上下文，按时间正序返回。"""
        with Session(self._engine) as session:
            messages = list(
                session.scalars(
                    select(Message)
                    .where(Message.conversation_id == conversation_id)
                    .order_by(Message.id.desc())
                    .limit(limit)
                )
            )
        return [{"role": m.role, "content": m.content} for m in reversed(messages)]
