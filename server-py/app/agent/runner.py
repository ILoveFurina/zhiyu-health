"""Agent 运行器：LangGraph 循环 + LLM seam。

AgentRunner 协议是 LLM seam：测试用 fake 替换，断言消息历史与推理档位。
生产实现经 langchain-openai 的 ChatOpenAI（OpenAI 兼容协议）接火山方舟，
reasoning_effort 作为扁平参数随 chat completions 请求体发送（ADR-0004）。
"""

import json
from collections.abc import AsyncIterator, Callable, Sequence
from dataclasses import dataclass
from typing import Any, Literal, Protocol

from langchain.agents import create_agent
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, ToolMessage
from langchain_core.tools import BaseTool
from langchain_openai import ChatOpenAI
from langgraph.graph.state import CompiledStateGraph
from pydantic import SecretStr

from app.agent.prompts import SYSTEM_PROMPT
from app.config import Settings, get_settings
from app.services.reasoning import ReasoningEffort


@dataclass(frozen=True)
class AgentContext:
    """由 server-java 注入且不暴露给模型的可信业务身份。"""

    patient_id: int
    conversation_id: int


@dataclass(frozen=True)
class AgentOutput:
    event: Literal[
        "token", "doctor_recommendations", "doctor_slots", "appointment", "appointments"
    ]
    data: str | dict[str, Any]


class AgentRunner(Protocol):
    def astream_reply(
        self, messages: list[dict[str, str]], effort: ReasoningEffort, context: AgentContext
    ) -> AsyncIterator[AgentOutput]:
        """按给定推理档位流式产出文本 token 或结构化工具结果。"""
        ...


class LangGraphAgentRunner:
    """langchain 1.x create_agent：空工具列表时即纯模型节点的对话循环。

    后续票（05/07 等）在此注入业务工具；映射后的 reasoning_effort 只有
    low/high 两档，因此按档位缓存编译图。
    """

    def __init__(
        self,
        model_factory: Callable[[ReasoningEffort], BaseChatModel],
        tools: Sequence[BaseTool] | None = None,
    ) -> None:
        self._model_factory = model_factory
        self._tools = list(tools or [])
        self._graphs: dict[str, CompiledStateGraph[Any, Any, Any, Any]] = {}

    def _graph(self, effort: ReasoningEffort) -> CompiledStateGraph[Any, Any, Any, Any]:
        if effort not in self._graphs:
            self._graphs[effort] = create_agent(
                self._model_factory(effort),
                tools=self._tools,
                system_prompt=SYSTEM_PROMPT,
                context_schema=AgentContext,
            )
        return self._graphs[effort]

    async def astream_reply(
        self, messages: list[dict[str, str]], effort: ReasoningEffort, context: AgentContext
    ) -> AsyncIterator[AgentOutput]:
        graph = self._graph(effort)
        lc_messages = _to_lc_messages(messages)
        async for item in graph.astream(
            {"messages": lc_messages}, context=context, stream_mode="messages"
        ):
            if not isinstance(item, tuple):
                continue
            chunk, metadata = item
            if isinstance(chunk, ToolMessage):
                event = _tool_event(chunk.name)
                if event is not None and isinstance(chunk.content, str):
                    try:
                        payload = json.loads(chunk.content)
                    except json.JSONDecodeError:
                        # 工具错误仍会回到模型解释；只有成功的结构化结果才投影成卡片。
                        continue
                    if isinstance(payload, dict):
                        yield AgentOutput(event, payload)
                continue
            if metadata.get("langgraph_node") != "model":
                continue
            content = chunk.content
            if isinstance(content, str) and content:
                yield AgentOutput("token", content)


def _tool_event(
    tool_name: str | None,
) -> Literal["doctor_recommendations", "doctor_slots", "appointment", "appointments"] | None:
    if tool_name == "recommend_doctors":
        return "doctor_recommendations"
    if tool_name == "get_doctor_slots":
        return "doctor_slots"
    if tool_name == "create_appointment":
        return "appointment"
    if tool_name == "get_appointment":
        return "appointments"
    return None


def _to_lc_messages(messages: list[dict[str, str]]) -> list[BaseMessage]:
    result: list[BaseMessage] = []
    for message in messages:
        if message["role"] == "user":
            result.append(HumanMessage(content=message["content"]))
        else:
            result.append(AIMessage(content=message["content"]))
    return result


def build_langgraph_agent_runner(
    settings: Settings, tools: Sequence[BaseTool] | None = None
) -> LangGraphAgentRunner:
    def model_factory(effort: ReasoningEffort) -> BaseChatModel:
        return ChatOpenAI(
            model=settings.doubao_chat_model,
            base_url=settings.ark_base_url,
            api_key=SecretStr(settings.ark_api_key),
            reasoning_effort=effort,
        )

    return LangGraphAgentRunner(model_factory, tools=tools)


class LazySettingsAgentRunner:
    """首次调用时才从 settings 构建生产 runner。

    让注入装配路径（测试用 SQLite + 不配置方舟环境变量）不依赖 settings，
    只有真正命中对话接口才读取方舟配置。
    """

    def __init__(self, tools: Sequence[BaseTool] | None = None) -> None:
        self._runner: LangGraphAgentRunner | None = None
        self._tools = tools

    async def astream_reply(
        self, messages: list[dict[str, str]], effort: ReasoningEffort, context: AgentContext
    ) -> AsyncIterator[AgentOutput]:
        if self._runner is None:
            self._runner = build_langgraph_agent_runner(get_settings(), self._tools)
        async for output in self._runner.astream_reply(messages, effort, context):
            yield output
