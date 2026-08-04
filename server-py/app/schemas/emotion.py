"""情绪反馈结构化输出契约（票 44，ADR-0019）。

server-py 在主回复 token 流完成后发起一次非流式 LLM 调用判断用户消息情绪，
产出 EmotionResult(emotion, rationale)；emotion 挂 message 事件下发，
rationale 仅调试用不下发。复用视觉管道结构化输出范式（json_object + pydantic 校验）。
"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.core.contracts import get_contracts


class EmotionResult(BaseModel):
    """主回复完成后串行二次 LLM 调用的结构化产物。

    emotion 取值限定为契约三档 calm/anxious/fearful；rationale 仅调试用，
    不下发到端侧（ADR-0019：失败/超时降级 calm，不阻塞回复）。
    """

    model_config = ConfigDict(extra="forbid")

    emotion: Literal["calm", "anxious", "fearful"]
    rationale: str = Field(min_length=1)

    @classmethod
    def calm_default(cls) -> "EmotionResult":
        """降级 calm：emotion 判断失败/超时的兜底产物。"""
        return cls(emotion="calm", rationale="降级：emotion 判断未成功")


def emotion_soothing_text(emotion: str | None) -> str | None:
    """安抚语：calm 无（返回 None），anxious/fearful 各一条确定性文案。

    文案唯一事实源是 contracts/emotion.json 的 soothing_texts，不由 LLM 现场生成。
    """
    if emotion is None:
        return None
    return get_contracts().emotion.soothing_texts.get(emotion)
