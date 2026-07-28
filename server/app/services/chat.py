"""对话编排：红线安全门 → 持久化 → Agent 流式回复 → 免责声明注入。

红线规则判断先于 LLM/工具循环执行（硬规则）；命中后立即中断导诊，
不调用 Agent。全部 AI 产出在事件负载中统一注入免责声明字段。
"""

import asyncio
from collections.abc import AsyncIterator

from app.agent.runner import AgentRunner
from app.core.constants import DISCLAIMER
from app.models import Message
from app.services.conversation import ConversationService
from app.services.reasoning import EffortChoice, ReasoningEffort, map_reasoning_effort
from app.services.red_flag import RedFlagHit, RedFlagService

# SSE 事件类型（与后续工具调用可视化同通道扩展）
EVENT_META = "meta"
EVENT_TOKEN = "token"
EVENT_MESSAGE = "message"
EVENT_RED_FLAG = "red_flag"
EVENT_DONE = "done"

RED_FLAG_WARNING_TEMPLATE = "检测到紧急危险信号：{rule}。{advice}。导诊已中断。"


class ChatService:
    def __init__(
        self,
        conversations: ConversationService,
        red_flag: RedFlagService,
        agent_runner: AgentRunner,
    ) -> None:
        self._conversations = conversations
        self._red_flag = red_flag
        self._agent_runner = agent_runner

    async def begin(
        self,
        *,
        patient_id: int,
        conversation_id: int | None,
        content: str,
        effort_choice: EffortChoice,
    ) -> AsyncIterator[dict[str, object]]:
        """准备一轮对话并返回事件流。会话无效抛 ConversationNotFoundError。"""
        conversation = await asyncio.to_thread(
            self._conversations.get_or_create_for_patient,
            patient_id,
            conversation_id,
            content,
        )
        await asyncio.to_thread(self._conversations.append_message, conversation.id, "user", content)

        hit = self._red_flag.judge(content)
        if hit is not None:
            warning = RED_FLAG_WARNING_TEMPLATE.format(rule=hit.rule_name, advice=hit.advice)
            saved = await asyncio.to_thread(
                self._conversations.append_message,
                conversation.id,
                "assistant",
                warning,
                "red_flag",
            )
            return self._red_flag_stream(conversation.id, saved, hit)

        effort = map_reasoning_effort(effort_choice, "triage")
        history = await asyncio.to_thread(self._conversations.recent_context, conversation.id)
        return self._agent_stream(conversation.id, history, effort)

    async def _red_flag_stream(
        self, conversation_id: int, saved: Message, hit: RedFlagHit
    ) -> AsyncIterator[dict[str, object]]:
        yield {"event": EVENT_META, "data": {"conversation_id": conversation_id}}
        yield {
            "event": EVENT_RED_FLAG,
            "data": {
                "message_id": saved.id,
                "rule": hit.rule_name,
                "content": saved.content,
                "advice": hit.advice,
            },
        }
        yield {"event": EVENT_DONE, "data": {}}

    async def _agent_stream(
        self, conversation_id: int, history: list[dict[str, str]], effort: ReasoningEffort
    ) -> AsyncIterator[dict[str, object]]:
        yield {"event": EVENT_META, "data": {"conversation_id": conversation_id, "effort": effort}}
        parts: list[str] = []
        async for token in self._agent_runner.astream_reply(history, effort):
            parts.append(token)
            yield {"event": EVENT_TOKEN, "data": {"text": token}}
        content = "".join(parts)
        saved = await asyncio.to_thread(
            self._conversations.append_message,
            conversation_id,
            "assistant",
            content,
            "text",
            effort,
        )
        yield {
            "event": EVENT_MESSAGE,
            "data": {
                "message_id": saved.id,
                "role": "assistant",
                "content": content,
                "disclaimer": DISCLAIMER,
                "effort": effort,
            },
        }
        yield {"event": EVENT_DONE, "data": {}}
