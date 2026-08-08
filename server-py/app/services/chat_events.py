"""把对话编排结果集中投影成项目 SSE 事件负载。

runner 输出只有在这里变成 token/thinking/tool/knowledge/card 事件；凡属于 AI 生成的
消息或卡片都注入契约免责声明，工具 trace 与知识召回事实不附免责声明。
"""

from app.agent.types import AgentOutput
from app.core.contracts import get_contracts
from app.schemas.emotion import emotion_soothing_text
from app.agent.emotion import EmotionJudge

_CONTRACTS = get_contracts()
EVENT_KNOWLEDGE = _CONTRACTS.sse_events.stream_events[1]
EVENT_TOKEN = _CONTRACTS.sse_events.stream_events[2]
EVENT_THINKING = _CONTRACTS.chat_realtime.thinking_event
EVENT_TOOL_START, EVENT_TOOL_END = _CONTRACTS.sse_events.trace_events


def project_agent_output(
    output: AgentOutput, parts: list[str], disclaimer: str
) -> dict[str, object] | None:
    if output.event == EVENT_TOKEN and isinstance(output.data, str):
        parts.append(output.data)
        return {"event": EVENT_TOKEN, "data": {"text": output.data}}
    if output.event == EVENT_THINKING and isinstance(output.data, str):
        return {"event": EVENT_THINKING, "data": output.data}
    if output.event == EVENT_KNOWLEDGE and isinstance(output.data, dict):
        return {"event": EVENT_KNOWLEDGE, "data": output.data}
    if output.event in (EVENT_TOOL_START, EVENT_TOOL_END):
        return {"event": output.event, "data": output.data}
    if isinstance(output.data, dict):
        return {"event": output.event, "data": {**output.data, "disclaimer": disclaimer}}
    return None


async def build_message_data(
    messages: list[dict[str, str]],
    parts: list[str],
    effort: str,
    emotion_judge: EmotionJudge,
    disclaimer: str,
) -> dict[str, object]:
    """情绪属于最终 message 负载，失败由 judge 降级 calm；预问诊摘要不在此阻塞。"""
    last_user_text = next(
        (message["content"] for message in reversed(messages) if message.get("role") == "user"),
        "",
    )
    emotion = await emotion_judge.judge(last_user_text)
    data: dict[str, object] = {
        "role": "assistant",
        "content": "".join(parts),
        "disclaimer": disclaimer,
        "effort": effort,
        "emotion": emotion.emotion,
    }
    soothing = emotion_soothing_text(emotion.emotion)
    if soothing is not None:
        data["soothing_text"] = soothing
    return data
