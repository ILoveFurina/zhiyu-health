"""语音双向（票 45）的内部 HTTP 契约：ASR 文字回执与 TTS 请求体。

ASR/TTS 不进 agent_call_logs trace（ADR-0020），响应不落库、不记原文。
"""

from pydantic import BaseModel, ConfigDict, Field


class AsrResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: str = Field(min_length=1)


class TtsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    text: str = Field(min_length=1, max_length=2000)
