"""Agent 对话编排：推理档位映射 → Agent 流式回复 → 免责声明注入。

server-py 只承载 LLM/工具循环与表达（ADR-0009）：鉴权、审计、红线规则与
会话持久化都在 server-java 入口侧。全部 AI 产出在事件负载中统一注入
免责声明（硬规则 1，server-java 出口另有兜底校验）。
"""

from collections.abc import AsyncIterator

from app.agent.runner import AgentRunner
from app.core.constants import DISCLAIMER
from app.services.reasoning import EffortChoice, Scenario, map_reasoning_effort

# SSE 事件类型（与后续工具调用可视化同通道扩展）
EVENT_META = "meta"
EVENT_TOKEN = "token"
EVENT_MESSAGE = "message"
EVENT_DONE = "done"


class AgentChatService:
    def __init__(self, agent_runner: AgentRunner) -> None:
        self._agent_runner = agent_runner

    async def stream(
        self,
        *,
        messages: list[dict[str, str]],
        effort_choice: EffortChoice,
        scenario: Scenario,
    ) -> AsyncIterator[dict[str, object]]:
        """对一段消息历史流式生成回复。auto 档位在此映射为 low/high，不外传。"""
        effort = map_reasoning_effort(effort_choice, scenario)
        yield {"event": EVENT_META, "data": {"effort": effort}}
        parts: list[str] = []
        async for token in self._agent_runner.astream_reply(messages, effort):
            parts.append(token)
            yield {"event": EVENT_TOKEN, "data": {"text": token}}
        yield {
            "event": EVENT_MESSAGE,
            "data": {
                "role": "assistant",
                "content": "".join(parts),
                "disclaimer": DISCLAIMER,
                "effort": effort,
            },
        }
        yield {"event": EVENT_DONE, "data": {}}
