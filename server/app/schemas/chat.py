from pydantic import BaseModel, Field

from app.services.reasoning import EffortChoice


class ChatRequest(BaseModel):
    content: str = Field(min_length=1)
    conversation_id: int | None = None
    effort: EffortChoice = "auto"


class MessageOut(BaseModel):
    id: int
    role: str
    kind: str
    content: str
    effort: str | None
    disclaimer: str | None
    created_at: str
