"""知识图谱增强（ADR-0013）：TestClient + fake 图遍历替身。

覆盖：graph 态先遍历后生成、none 态不遍历、空召回降级走裸 LLM、
knowledge 元事件状态正确、graph 与 rag 互斥不注入对方工具、投影接口。
"""

import json
from collections.abc import Callable, Iterator, Sequence
from typing import Any

from conftest import TEST_AGENT_SECRET, FakeGraphTraverser, FakeKnowledgeRetriever, StubHealthService
from fastapi.testclient import TestClient
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage, ToolCall
from langchain_core.tools import BaseTool

from app.agent.runner import LangGraphAgentRunner
from app.main import create_app
from app.tools.graph import GraphNeighbor


def _parse_events(raw: str) -> list[dict]:
    events = []
    for frame in raw.split("\n\n"):
        if not frame.strip():
            continue
        lines = frame.strip().split("\n")
        event = lines[0].removeprefix("event: ")
        data = lines[1].removeprefix("data: ")
        events.append({"event": event, "data": json.loads(data)})
    return events


def _post_chat(client, payload: dict) -> list[dict]:
    payload = {"patient_id": 12, "conversation_id": 7, **payload}
    with client.stream(
        "POST",
        "/api/agent/chat",
        json=payload,
        headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
    ) as response:
        assert response.status_code == 200
        raw = "".join(response.iter_text())
    return _parse_events(raw)


class _ToolCallingFake(GenericFakeChatModel):
    """脚本化工具调用 fake；bind_tools 默认 no-op，子类可覆盖以记录工具名。"""

    messages: Iterator[AIMessage | str]

    def bind_tools(
        self,
        tools: Sequence[dict[str, Any] | type | Callable[..., Any] | BaseTool],
        *,
        tool_choice: str | None = None,
        **kwargs: Any,
    ) -> Any:
        return self


def _recording_fake(
    scripted: Iterator[AIMessage | str], bound_names: list[str]
) -> _ToolCallingFake:
    """构造记录注入工具名的 fake：bind_tools 时把工具名写入外部列表。"""

    class _Recording(_ToolCallingFake):
        def bind_tools(
            self,
            tools: Sequence[dict[str, Any] | type | Callable[..., Any] | BaseTool],
            *,
            tool_choice: str | None = None,
            **kwargs: Any,
        ) -> Any:
            bound_names.clear()
            bound_names.extend(t.name for t in tools)
            return self

    return _Recording(disable_streaming=True, messages=scripted)


def _build_app(
    fake: _ToolCallingFake,
    graph_traverser: FakeGraphTraverser | None,
    *,
    graph_available: bool = True,
) -> TestClient:
    """构造注入 graph_traverser 的测试 app（不注入 rag 检索器，确保互斥）。"""
    runner = LangGraphAgentRunner(lambda effort: fake, graph_traverser=graph_traverser)
    app = create_app(
        health_service=StubHealthService(),
        agent_runner=runner,
        agent_auth_secret=TEST_AGENT_SECRET,
        graph_available=graph_available,
    )
    return TestClient(app)


_NEIGHBORS = [
    GraphNeighbor(name="心律失常", node_type="Disease", relation="INDICATES", direction="outgoing"),
    GraphNeighbor(name="心血管内科", node_type="Department", relation="SUGGESTS_DEPARTMENT", direction="outgoing"),
]


def test_graph_traverses_before_generation_and_emits_ok_event() -> None:
    """graph 态先遍历后生成，knowledge 事件 source=graph/status=ok。"""
    traverser = FakeGraphTraverser(_NEIGHBORS)
    fake = _ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="traverse_graph", args={"entities": ["胸闷气短"]}, id="g-1")
        ]),
        "结合图谱检索，胸闷气短可能关联心律失常，建议到心血管内科评估。",
    ]))
    client = _build_app(fake, traverser)
    with client:
        events = _post_chat(client, {
            "messages": [{"role": "user", "content": "最近胸闷气短"}],
            "knowledge_source": "graph",
        })

    # 先遍历后生成：traverser 在 LLM 生成最终文本前被调用
    assert traverser.calls == [["胸闷气短"]]
    kinds = [e["event"] for e in events]
    assert kinds == ["meta", "knowledge", "token", "message", "done"]
    knowledge = events[1]
    assert knowledge["data"] == {"source": "graph", "status": "ok", "count": 2}
    # 最终文本引用了图谱检索内容
    assert "心血管内科" in events[-2]["data"]["content"]


def test_none_source_does_not_inject_traverse_graph_tool() -> None:
    """none 态不注入 traverse_graph：traverser 未被调用，无 knowledge 事件。"""
    traverser = FakeGraphTraverser(_NEIGHBORS)
    bound_names: list[str] = []
    fake = _recording_fake(iter(["我没有检索，直接回答。"]), bound_names)
    client = _build_app(fake, traverser)
    with client:
        events = _post_chat(client, {
            "messages": [{"role": "user", "content": "感冒怎么办"}],
            "knowledge_source": "none",
        })

    assert traverser.calls == []
    assert "knowledge" not in [e["event"] for e in events]
    assert bound_names == []  # 工具集不含 traverse_graph


def test_graph_empty_recall_degrades_to_bare_llm_with_degraded_event() -> None:
    """graph 空召回标 degraded，仍产出 message + done（降级不中断流）。"""
    traverser = FakeGraphTraverser(neighbors=[])  # 空召回
    fake = _ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="traverse_graph", args={"entities": ["罕见症状"]}, id="g-2")
        ]),
        "未检索到图谱信息，基于自身知识回答。",
    ]))
    client = _build_app(fake, traverser)
    with client:
        events = _post_chat(client, {
            "messages": [{"role": "user", "content": "罕见症状"}],
            "knowledge_source": "graph",
        })

    knowledge = next(e for e in events if e["event"] == "knowledge")
    assert knowledge["data"] == {"source": "graph", "status": "degraded", "count": 0}
    assert events[-2]["event"] == "message"
    assert events[-1]["event"] == "done"


def test_graph_traversal_failure_degrades_silently() -> None:
    """遍历异常被吞 -> 空召回 -> count=0 -> degraded（降级纪律与 rag 对称）。

    生产 Neo4jGraphTraverser 内部捕获 Neo4jError/OSError 返回空（见 test_traverser_swallows_exceptions）。
    此处用空召回 fake 模拟"遍历失败已被吞掉"后的工具视角：count=0 -> degraded。
    """
    traverser = FakeGraphTraverser(neighbors=[])
    fake = _ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="traverse_graph", args={"entities": ["头痛"]}, id="g-3")
        ]),
        "图谱暂时不可用，我基于自身知识回答。",
    ]))
    client = _build_app(fake, traverser)
    with client:
        events = _post_chat(client, {
            "messages": [{"role": "user", "content": "头痛"}],
            "knowledge_source": "graph",
        })

    knowledge = next(e for e in events if e["event"] == "knowledge")
    assert knowledge["data"]["status"] == "degraded"
    assert knowledge["data"]["count"] == 0


def test_traverser_swallows_exceptions_and_returns_empty() -> None:
    """Neo4jGraphTraverser 内部捕获任何异常返回空，不向工具/对话抛出（降级纪律）。"""
    import asyncio

    from app.services.graph import Neo4jGraphTraverser

    class _ExplodingDriver:
        def session(self, **kwargs):
            raise OSError("Neo4j 不可用")

    traverser = Neo4jGraphTraverser(driver=_ExplodingDriver())  # type: ignore[arg-type]
    result = asyncio.run(traverser.traverse(["头痛"]))
    assert result == []


def test_graph_and_rag_are_mutually_exclusive() -> None:
    """graph 态不注入 search_knowledge（grilling 决策 2：互斥注入）。"""
    # 同时提供 retriever 和 traverser，但 knowledge_source=graph 时只应注入 traverse_graph
    retriever = FakeKnowledgeRetriever()
    traverser = FakeGraphTraverser(_NEIGHBORS)
    bound_names: list[str] = []
    fake = _recording_fake(iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="traverse_graph", args={"entities": ["胸闷"]}, id="g-4")
        ]),
        "回答。",
    ]), bound_names)
    runner = LangGraphAgentRunner(
        lambda effort: fake, knowledge_retriever=retriever, graph_traverser=traverser
    )
    app = create_app(
        health_service=StubHealthService(),
        agent_runner=runner,
        agent_auth_secret=TEST_AGENT_SECRET,
        graph_available=True,
    )
    with TestClient(app) as client:
        _post_chat(client, {
            "messages": [{"role": "user", "content": "胸闷"}],
            "knowledge_source": "graph",
        })

    # graph 态只注入 traverse_graph，不注入 search_knowledge
    assert "search_knowledge" not in bound_names
    assert "traverse_graph" in bound_names
    # retriever 未被调用（互斥）
    assert retriever.calls == []
    assert traverser.calls == [["胸闷"]]


def test_graph_unavailable_when_requested_emits_unavailable_event() -> None:
    """graph 选中但遍历器未配置（graph_available=False）-> unavailable 降级。"""
    traverser = FakeGraphTraverser(_NEIGHBORS)
    fake = _ToolCallingFake(disable_streaming=True, messages=iter([
        "图谱不可用，走裸 LLM。",
    ]))
    client = _build_app(fake, traverser, graph_available=False)
    with client:
        events = _post_chat(client, {
            "messages": [{"role": "user", "content": "症状"}],
            "knowledge_source": "graph",
        })

    knowledge = next(e for e in events if e["event"] == "knowledge")
    assert knowledge["data"] == {"source": "graph", "status": "unavailable", "count": 0}
    assert traverser.calls == []  # 降级不调用遍历器


def test_graph_event_has_no_disclaimer_but_message_does() -> None:
    """knowledge 元事件不带免责声明（非 AI 产出）；message 带（硬约束 1）。"""
    traverser = FakeGraphTraverser(_NEIGHBORS)
    fake = _ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="traverse_graph", args={"entities": ["胸闷"]}, id="g-5")
        ]),
        "建议就医。",
    ]))
    client = _build_app(fake, traverser)
    with client:
        events = _post_chat(client, {
            "messages": [{"role": "user", "content": "胸闷"}],
            "knowledge_source": "graph",
        })

    knowledge = next(e for e in events if e["event"] == "knowledge")
    assert "disclaimer" not in knowledge["data"]
    message = next(e for e in events if e["event"] == "message")
    assert message["data"]["disclaimer"] == "仅供参考，不替代医生诊断"


# ========== 图谱投影接口测试 ==========


class _FakeProjector:
    """投影 service 的 fake：可控投影/详情结果。"""

    def __init__(self, projection_data: dict | None = None, detail_data: dict | None = None) -> None:
        self._projection = projection_data or {"nodes": [], "edges": []}
        self._detail = detail_data

    async def projection(self) -> dict:
        return self._projection

    async def node_detail(self, node_id: str) -> dict | None:
        return self._detail


def _build_app_with_projector(projector: object | None) -> TestClient:
    app = create_app(
        health_service=StubHealthService(),
        agent_auth_secret=TEST_AGENT_SECRET,
        graph_projector=projector,
    )
    return TestClient(app)


def test_projection_returns_nodes_and_edges() -> None:
    """投影接口返回 {nodes, edges} 最小拓扑骨架（ADR-0013 决策 6）。"""
    projector = _FakeProjector(
        projection_data={
            "nodes": [
                {"id": "symptom:胸闷气短", "label": "胸闷气短", "group": "Symptom"},
                {"id": "disease:心律失常", "label": "心律失常", "group": "Disease"},
            ],
            "edges": [
                {"source": "symptom:胸闷气短", "target": "disease:心律失常", "type": "INDICATES"},
            ],
        }
    )
    client = _build_app_with_projector(projector)
    with client:
        response = client.get(
            "/api/knowledge/graph",
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    data = response.json()
    assert len(data["nodes"]) == 2
    assert len(data["edges"]) == 1
    assert data["edges"][0]["type"] == "INDICATES"


def test_projection_returns_empty_when_projector_unavailable() -> None:
    """投影器未配置时返回空图，不抛错给 B 端（降级展示空图）。"""
    client = _build_app_with_projector(None)
    with client:
        response = client.get(
            "/api/knowledge/graph",
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    assert response.json() == {"nodes": [], "edges": []}


def test_node_detail_returns_properties() -> None:
    """点击节点取详情：返回节点类型与全部属性。"""
    projector = _FakeProjector(
        detail_data={
            "node_id": "symptom:胸闷气短",
            "node_type": "Symptom",
            "name": "胸闷气短",
            "aliases": ["胸闷", "气短"],
            "department": "心血管内科",
        }
    )
    client = _build_app_with_projector(projector)
    with client:
        response = client.get(
            "/api/knowledge/graph/node",
            params={"node_id": "symptom:胸闷气短"},
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 200
    data = response.json()
    assert data["node_type"] == "Symptom"
    assert data["aliases"] == ["胸闷", "气短"]


def test_node_detail_404_when_not_found() -> None:
    """节点不存在时返回 404。"""
    projector = _FakeProjector(detail_data=None)
    client = _build_app_with_projector(projector)
    with client:
        response = client.get(
            "/api/knowledge/graph/node",
            params={"node_id": "symptom:不存在"},
            headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
        )
    assert response.status_code == 404


def test_projection_requires_callback_auth() -> None:
    """投影接口需 AgentCallbackAuth（server-java 回调令牌）。"""
    client = _build_app_with_projector(_FakeProjector())
    with client:
        response = client.get("/api/knowledge/graph")
    assert response.status_code == 401
