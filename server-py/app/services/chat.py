"""Agent 对话编排：推理档位映射 → Agent 流式回复 → 免责声明注入。

server-py 只承载 LLM/工具循环与表达（ADR-0009）：鉴权、审计、红线规则与
会话持久化都在 server-java 入口侧。全部 AI 产出在事件负载中统一注入
免责声明（硬规则 1，server-java 出口另有兜底校验）。
"""

from collections.abc import AsyncIterator

from app.agent.runner import AgentContext, AgentRunner, HealthProfileContext
from app.core.contracts import get_contracts
from app.services.reasoning import EffortChoice, Scenario, map_reasoning_effort

# SSE 流事件名唯一事实源是 contracts/sse-events.json；协议顺序固定，
# 由 tests/test_contract_consumption.py 与 java 侧 ContractsConsistencyTest 双端钉死。
EVENT_META, EVENT_KNOWLEDGE, EVENT_TOKEN, EVENT_MESSAGE, EVENT_DONE = (
    get_contracts().sse_events.stream_events
)


class AgentChatService:
    def __init__(self, agent_runner: AgentRunner) -> None:
        self._agent_runner = agent_runner
        # 免责声明唯一事实源是跨栈契约 contracts/disclaimer.json（硬约束 1），
        # 装配期取出缓存，禁止在本地另立文案常量。
        self._disclaimer = get_contracts().disclaimer.text

    async def stream(
        self,
        *,
        messages: list[dict[str, str]],
        patient_id: int,
        conversation_id: int,
        health_profile: HealthProfileContext | None = None,
        effort_choice: EffortChoice,
        scenario: Scenario,
        longitude: float | None = None,
        latitude: float | None = None,
    ) -> AsyncIterator[dict[str, object]]:
        """对一段消息历史流式生成回复。auto 档位在此映射为 low/high，不外传。"""
        effort = map_reasoning_effort(effort_choice, scenario)
        yield {"event": EVENT_META, "data": {"effort": effort}}
        parts: list[str] = []
        terminated_by_contraindication = False
        context = AgentContext(
            patient_id=patient_id,
            conversation_id=conversation_id,
            health_profile=health_profile,
            longitude=longitude,
            latitude=latitude,
        )
        async for output in self._agent_runner.astream_reply(messages, effort, context):
            if output.event == EVENT_TOKEN and isinstance(output.data, str):
                parts.append(output.data)
                yield {"event": EVENT_TOKEN, "data": {"text": output.data}}
            elif isinstance(output.data, dict):
                yield {
                    "event": output.event,
                    "data": {**output.data, "disclaimer": self._disclaimer},
                }
                contraindication_event = get_contracts().sse_events.tool_to_event[
                    "check_contraindication"
                ]
                if output.event == contraindication_event and output.data.get("blocked") is True:
                    # 规则卡片已经给出固定原因与建议；立即关闭图流，阻止模型继续输出
                    # 未经复检的替代药或覆盖规则决定。
                    terminated_by_contraindication = True
                    break
        if not terminated_by_contraindication:
            yield {
                "event": EVENT_MESSAGE,
                "data": {
                    "role": "assistant",
                    "content": "".join(parts),
                    "disclaimer": self._disclaimer,
                    "effort": effort,
                },
            }
        yield {"event": EVENT_DONE, "data": {}}
