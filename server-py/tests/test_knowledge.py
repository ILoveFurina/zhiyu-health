"""知识增强 RAG（ADR-0010）：TestClient + fake embedding/检索替身。

覆盖：rag 态先检索后生成、none 态不检索、空召回/失败降级走裸 LLM、
knowledge 元事件状态正确、召回块格式「标题·科室」正文、免责声明注入边界。
"""

import json
from collections.abc import Callable, Iterator, Sequence
from typing import Any

from conftest import TEST_AGENT_SECRET, FakeEmotionJudge, FakeKnowledgeRetriever, StubHealthService
from fastapi.testclient import TestClient
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage, ToolCall
from langchain_core.tools import BaseTool

from app.agent.runner import LangGraphAgentRunner
from app.main import create_app
from app.tools.knowledge import KnowledgeChunk, KnowledgeRetriever


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
    retriever: KnowledgeRetriever | None,
    *,
    rag_available: bool = True,
) -> TestClient:
    runner = LangGraphAgentRunner(lambda effort: fake, knowledge_retriever=retriever)
    app = create_app(
        health_service=StubHealthService(),
        agent_runner=runner,
        agent_auth_secret=TEST_AGENT_SECRET,
        rag_available=rag_available,
        emotion_judge=FakeEmotionJudge(),
    )
    return TestClient(app)


_CHUNKS = [
    KnowledgeChunk(
        text="【胸闷气短·心血管内科】常见于情绪紧张、劳累或轻度心律失常。建议到心血管内科评估。",
        department="心血管内科",
        score=0.82,
    )
]


def test_rag_retrieves_before_generation_and_emits_ok_event() -> None:
    retriever = FakeKnowledgeRetriever(_CHUNKS)
    fake = _ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="search_knowledge", args={"query": "胸闷气短"}, id="k-1")
        ]),
        "结合检索到的知识，胸闷气短建议到心血管内科评估。",
    ]))
    client = _build_app(fake, retriever)
    with client:
        events = _post_chat(client, {
            "messages": [{"role": "user", "content": "最近胸闷气短"}],
            "knowledge_source": "rag",
        })

    # 先检索后生成：retriever 在 LLM 生成最终文本前被调用
    assert retriever.calls == ["胸闷气短"]
    kinds = [e["event"] for e in events]
    # 知识工具只发 tool_end（不发 tool_start），其结果由 knowledge 元事件承担（票 24）
    assert kinds == ["meta", "tool_end", "knowledge", "token", "message", "done"]
    assert events[1]["data"]["tool_name"] == "search_knowledge"
    assert events[1]["data"]["result"] == "success"
    knowledge = events[2]
    assert knowledge["data"] == {"source": "rag", "status": "ok", "count": 1}
    # 最终文本引用了检索内容
    assert "心血管内科" in events[-2]["data"]["content"]


def test_none_source_does_not_inject_search_knowledge_tool() -> None:
    retriever = FakeKnowledgeRetriever(_CHUNKS)
    bound_names: list[str] = []
    fake = _recording_fake(iter(["我没有检索，直接回答。"]), bound_names)
    client = _build_app(fake, retriever)
    with client:
        events = _post_chat(client, {
            "messages": [{"role": "user", "content": "感冒怎么办"}],
            "knowledge_source": "none",
        })

    # none 态不注入 search_knowledge：retriever 未被调用，无 knowledge 事件
    assert retriever.calls == []
    assert "knowledge" not in [e["event"] for e in events]
    assert bound_names == []  # 工具集不含 search_knowledge


def test_empty_recall_degrades_to_bare_llm_with_degraded_event() -> None:
    retriever = FakeKnowledgeRetriever(chunks=[])  # 空召回
    fake = _ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="search_knowledge", args={"query": "罕见症状"}, id="k-2")
        ]),
        "未检索到相关知识，基于自身知识回答。",
    ]))
    client = _build_app(fake, retriever)
    with client:
        events = _post_chat(client, {
            "messages": [{"role": "user", "content": "罕见症状"}],
            "knowledge_source": "rag",
        })

    knowledge = next(e for e in events if e["event"] == "knowledge")
    assert knowledge["data"] == {"source": "rag", "status": "degraded", "count": 0}
    # 降级不中断流：仍产出 message + done
    assert events[-2]["event"] == "message"
    assert events[-1]["event"] == "done"


def test_retrieval_failure_degrades_silently() -> None:
    # 生产 PgvectorKnowledgeRetriever 内部捕获异常返回空（见 test_pgvector_retriever）。
    # 此处用空召回 fake 模拟"检索失败已被吞掉"后的工具视角：count=0 -> degraded。
    retriever = FakeKnowledgeRetriever(chunks=[])
    fake = _ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="search_knowledge", args={"query": "头痛"}, id="k-3")
        ]),
        "检索暂时不可用，我基于自身知识回答。",
    ]))
    client = _build_app(fake, retriever)
    with client:
        events = _post_chat(client, {
            "messages": [{"role": "user", "content": "头痛"}],
            "knowledge_source": "rag",
        })

    knowledge = next(e for e in events if e["event"] == "knowledge")
    # 检索异常被吞 -> 空召回 -> count=0 -> degraded
    assert knowledge["data"]["status"] == "degraded"
    assert knowledge["data"]["count"] == 0


def test_pgvector_retriever_swallows_exceptions_and_returns_empty() -> None:
    # PgvectorKnowledgeRetriever 内部捕获任何异常返回空，不向工具/对话抛出（降级纪律）
    import asyncio

    from app.services.knowledge import PgvectorKnowledgeRetriever

    class _ExplodingEmbedder:
        async def aembed_query(self, query: str) -> list[float]:
            raise RuntimeError("embedding endpoint 不可用")

    retriever = PgvectorKnowledgeRetriever(
        dsn="postgresql://invalid", embedder=_ExplodingEmbedder(), top_k=3, threshold=0.3
    )
    result = asyncio.run(retriever.search("头痛"))
    assert result == []


def test_knowledge_event_has_no_disclaimer_but_tokens_and_message_do() -> None:
    retriever = FakeKnowledgeRetriever(_CHUNKS)
    fake = _ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="search_knowledge", args={"query": "胸闷"}, id="k-4")
        ]),
        "建议就医。",
    ]))
    client = _build_app(fake, retriever)
    with client:
        events = _post_chat(client, {
            "messages": [{"role": "user", "content": "胸闷"}],
            "knowledge_source": "rag",
        })

    knowledge = next(e for e in events if e["event"] == "knowledge")
    # knowledge 元事件不带免责声明（非 AI 产出）
    assert "disclaimer" not in knowledge["data"]
    # message 带免责声明（硬约束 1）
    message = next(e for e in events if e["event"] == "message")
    assert message["data"]["disclaimer"] == "仅供参考，不替代医生诊断"


def test_rag_unavailable_when_scenario_defaults_rag_emits_degraded() -> None:
    # 检索器不可用（rag_available=False），场景默认 triage->rag 应降级
    retriever = FakeKnowledgeRetriever(_CHUNKS)
    fake = _ToolCallingFake(disable_streaming=True, messages=iter([
        "我基于自身知识回答。",
    ]))
    client = _build_app(fake, retriever, rag_available=False)
    with client:
        events = _post_chat(client, {
            "messages": [{"role": "user", "content": "咳嗽"}],
            # 不传 knowledge_source，走 scenario=triage 默认 rag
        })

    knowledge = next(e for e in events if e["event"] == "knowledge")
    assert knowledge["data"] == {"source": "rag", "status": "degraded", "count": 0}
    # none 态不注入工具，retriever 未被调用
    assert retriever.calls == []


def test_graph_source_unavailable_degrades() -> None:
    retriever = FakeKnowledgeRetriever(_CHUNKS)
    fake = _ToolCallingFake(disable_streaming=True, messages=iter([
        "graph 未实现，走裸 LLM。",
    ]))
    client = _build_app(fake, retriever)
    with client:
        events = _post_chat(client, {
            "messages": [{"role": "user", "content": "症状"}],
            "knowledge_source": "graph",
        })

    knowledge = next(e for e in events if e["event"] == "knowledge")
    assert knowledge["data"] == {"source": "graph", "status": "unavailable", "count": 0}
    assert retriever.calls == []  # graph 不检索


def test_recall_chunk_format_is_title_department_content() -> None:
    retriever = FakeKnowledgeRetriever(_CHUNKS)
    fake = _ToolCallingFake(disable_streaming=True, messages=iter([
        AIMessage(content="", tool_calls=[
            ToolCall(name="search_knowledge", args={"query": "胸闷"}, id="k-5")
        ]),
        "回答。",
    ]))
    client = _build_app(fake, retriever)
    with client:
        _post_chat(client, {
            "messages": [{"role": "user", "content": "胸闷"}],
            "knowledge_source": "rag",
        })

    # 召回块经 search_knowledge 工具返回给 LLM，格式为【标题·科室】正文
    assert _CHUNKS[0].text.startswith("【胸闷气短·心血管内科】")
