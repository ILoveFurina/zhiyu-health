"""LangGraph 接线测试：用 langchain_core 的 GenericFakeChatModel 验证

create_agent 图、stream_mode="messages" 过滤与消息转换，不触网。
多轮上下文在 HTTP seam 的覆盖见 test_chat_api.py。
"""

import asyncio
from collections.abc import Iterator
from typing import Any

from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.outputs import ChatResult

from app.agent.runner import LangGraphAgentRunner


def _collect(runner: LangGraphAgentRunner, messages: list[dict[str, str]]) -> str:
    async def run() -> str:
        parts = [token async for token in runner.astream_reply(messages, "low")]
        return "".join(parts)

    return asyncio.run(run())


def test_runner_streams_model_reply_tokens() -> None:
    runner = LangGraphAgentRunner(
        lambda effort: GenericFakeChatModel(messages=iter(["多喝温水，注意休息。"]))
    )

    assert _collect(runner, [{"role": "user", "content": "我咳嗽了"}]) == "多喝温水，注意休息。"


def test_runner_feeds_system_prompt_and_full_history_to_model() -> None:
    seen: list[list[tuple[str, Any]]] = []

    class RecordingFakeModel(GenericFakeChatModel):
        messages: Iterator[AIMessage | str]

        def _generate(
            self,
            messages: list[BaseMessage],
            stop: list[str] | None = None,
            run_manager: CallbackManagerForLLMRun | None = None,
            **kwargs: Any,
        ) -> ChatResult:
            seen.append([(m.type, m.content) for m in messages])
            return super()._generate(messages, stop, run_manager, **kwargs)

    runner = LangGraphAgentRunner(lambda effort: RecordingFakeModel(messages=iter(["好的"])))
    history = [
        {"role": "user", "content": "我咳嗽三天了"},
        {"role": "assistant", "content": "有发烧吗？"},
        {"role": "user", "content": "昨晚开始发烧"},
    ]

    assert _collect(runner, history) == "好的"

    assert len(seen) == 1
    received = seen[0]
    assert received[0][0] == "system"  # 人设 system prompt 在最前
    assert received[1:] == [
        ("human", "我咳嗽三天了"),
        ("ai", "有发烧吗？"),
        ("human", "昨晚开始发烧"),
    ]
