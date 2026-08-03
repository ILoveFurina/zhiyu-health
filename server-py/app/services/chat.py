"""Agent 对话编排：推理档位映射 -> 知识源选择 -> Agent 流式回复 -> 免责声明注入。

server-py 只承载 LLM/工具循环与表达（ADR-0009）：鉴权、审计、红线规则与
会话持久化都在 server-java 入口侧。全部 AI 产出在事件负载中统一注入
免责声明（硬规则 1，server-java 出口另有兜底校验）。

知识增强（ADR-0010）：知识源选择器模型 Y 二态 rag/graph + 自动降级。默认按
场景（triage->rag，interpretation->none）；rag 注入 search_knowledge 工具、graph
注入 traverse_graph 工具（LLM 自主调用，互斥），关闭增强不注入工具（裸 LLM）。
检索失败/空召回静默降级走裸 LLM；graph 遍历器未配置视为 unavailable 降级。
降级/不可用状态经 SSE knowledge 元事件暴露。
召回块与 knowledge 元事件不带免责声明（非 AI 产出）。
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

# 工具进度事件名（票 24）：tool_start/tool_end，不带免责声明（非 AI 产出）。
EVENT_TOOL_START, EVENT_TOOL_END = get_contracts().sse_events.trace_events


class AgentChatService:
    def __init__(
        self,
        agent_runner: AgentRunner,
        *,
        rag_available: bool = False,
        graph_available: bool = False,
    ) -> None:
        self._agent_runner = agent_runner
        self._rag_available = rag_available
        self._graph_available = graph_available
        # 免责声明唯一事实源是跨栈契约 contracts/disclaimer.json（硬约束 1），
        # 装配期取出缓存，禁止在本地另立文案常量。
        self._disclaimer = get_contracts().disclaimer.text
        self._knowledge = get_contracts().knowledge

    def _resolve_knowledge_source(self, requested: str | None, scenario: Scenario) -> str:
        """知识源选择器（模型 Y）：默认按场景；graph 需遍历器可用；rag 需检索器可用。"""
        contract = self._knowledge
        source = requested if requested is not None else contract.default_by_scenario[scenario]
        if source not in contract.knowledge_sources:
            return contract.none_source
        if source == "rag" and not self._rag_available:
            # rag 选中但检索器未配置：降级走裸 LLM（knowledge 事件标 degraded）
            return contract.none_source
        if source == "graph" and not self._graph_available:
            # graph 选中但遍历器未配置：降级走裸 LLM（knowledge 事件标 unavailable）
            return contract.none_source
        return source

    def _degraded_knowledge_event(
        self, requested: str | None, scenario: Scenario
    ) -> dict[str, object] | None:
        """降级/不可用时返回 knowledge 元事件；成功检索的 ok 事件由 runner 产出。"""
        contract = self._knowledge
        # 请求态（用户显式或场景默认），用于判断是否本应走增强却降级
        requested_source = (
            requested if requested is not None else contract.default_by_scenario[scenario]
        )
        if requested_source == "graph" and not self._graph_available:
            return {"source": "graph", "status": "unavailable", "count": 0}
        if requested_source == "rag" and not self._rag_available:
            return {"source": "rag", "status": "degraded", "count": 0}
        return None

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
        knowledge_source: str | None = None,
    ) -> AsyncIterator[dict[str, object]]:
        """对一段消息历史流式生成回复。auto 在此映射为 disabled/high，不外传。"""
        effort = map_reasoning_effort(effort_choice, scenario)
        yield {"event": EVENT_META, "data": {"effort": effort}}

        effective = self._resolve_knowledge_source(knowledge_source, scenario)
        # 降级/不可用由 service 在 meta 后发 knowledge 元事件；
        # 成功检索（status=ok）的 knowledge 事件由 runner 在工具调用时产出。
        degraded = self._degraded_knowledge_event(knowledge_source, scenario)
        if degraded is not None:
            yield {"event": EVENT_KNOWLEDGE, "data": degraded}

        parts: list[str] = []
        context = AgentContext(
            patient_id=patient_id,
            conversation_id=conversation_id,
            health_profile=health_profile,
            longitude=longitude,
            latitude=latitude,
            knowledge_source=effective,
        )
        async for output in self._agent_runner.astream_reply(messages, effort, context):
            if output.event == EVENT_TOKEN and isinstance(output.data, str):
                parts.append(output.data)
                yield {"event": EVENT_TOKEN, "data": {"text": output.data}}
            elif output.event == EVENT_KNOWLEDGE and isinstance(output.data, dict):
                # runner 在 search_knowledge 成功检索时产出；不带免责声明（非 AI 产出）
                yield {"event": EVENT_KNOWLEDGE, "data": output.data}
            elif output.event in (EVENT_TOOL_START, EVENT_TOOL_END):
                # 工具进度事件（票 24）：无原文、无免责声明（非 AI 产出）。
                # tool_call_id 作为 start/end 配对键透传，duration_ms 由 server-java 墙钟计算。
                yield {"event": output.event, "data": output.data}
            elif isinstance(output.data, dict):
                yield {
                    "event": output.event,
                    "data": {**output.data, "disclaimer": self._disclaimer},
                }
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
