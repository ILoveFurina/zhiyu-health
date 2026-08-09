"""测试基座：fake Agent 替换 LLM。

seam 纪律：主 seam 为 FastAPI HTTP API 层；LLM 以 FakeAgentRunner 替换，
断言它对消息历史与推理档位的接收情况。
"""

from collections.abc import AsyncIterator, Iterator
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.agent.runner import AgentContext, AgentOutput
from app.testing import create_test_app
from app.schemas.emotion import EmotionResult
from app.schemas.preconsult import PreconsultationSummary
from app.schemas.triage import TriageResolution
from app.tools.graph import GraphNeighbor
from app.tools.knowledge import KnowledgeChunk

TEST_AGENT_SECRET = "test-only-agent-callback-secret"


class StubHealthService:
    async def check(self) -> dict[str, object]:
        return {
            "status": "ok",
            "services": {
                "neo4j": {"status": "ok"},
                "pgvector": {"status": "ok"},
            },
        }


class FakeAgentRunner:
    """LLM seam 的 fake：记录收到的消息历史与推理档位，回固定 token 流。"""

    def __init__(self, tokens: list[str] | None = None) -> None:
        self.tokens = tokens or ["你好", "，我是小愈", "。"]
        self.calls: list[dict[str, object]] = []

    async def astream_reply(
        self, messages: list[dict[str, str]], effort: str, context: AgentContext
    ) -> AsyncIterator[AgentOutput]:
        self.calls.append({"messages": messages, "effort": effort, "context": context})
        for token in self.tokens:
            yield AgentOutput("token", token)


class FakeEmotionJudge:
    """情绪判断 seam 的 fake：固定返回 calm，记录收到的用户文本。"""

    def __init__(self, result: EmotionResult | None = None) -> None:
        self._result = result or EmotionResult.calm_default()
        self.calls: list[str] = []

    async def judge(self, user_text: str) -> EmotionResult:
        self.calls.append(user_text)
        return self._result


class FakeTriageJudge:
    """科室解析 seam 的 fake（票 50）：可编排返回序列，记录收到的消息与候选科室。"""

    def __init__(self, results: list[TriageResolution] | None = None) -> None:
        self._results = list(results or [])
        self.calls: list[dict[str, object]] = []

    async def judge(self, messages, candidates) -> TriageResolution:
        self.calls.append({"messages": messages, "candidates": candidates})
        if self._results:
            return self._results.pop(0)
        return TriageResolution.none_default()


class FakePreconsultJudge:
    """预问诊摘要 seam 的 fake（票 55）：可编排返回序列（None=本轮无快照），记录调用。

    raises=True 模拟判定器异常：编排层必须降级为省略快照字段，不得掐断 SSE 流。
    """

    def __init__(
        self,
        results: list[PreconsultationSummary | None] | None = None,
        *,
        raises: bool = False,
    ) -> None:
        self._results = list(results or [])
        self._raises = raises
        self.calls: list[dict[str, object]] = []

    async def judge(self, messages, candidates) -> PreconsultationSummary | None:
        self.calls.append({"messages": messages, "candidates": candidates})
        if self._raises:
            raise RuntimeError("摘要整理失败（fake）")
        if self._results:
            return self._results.pop(0)
        return None


class FakeSummaryCallback:
    """摘要异步回调 seam 的 fake（票 55 改造）：记录 draftId 与 payload，供断言回调时序。

    不发起真实 HTTP；apply 直接记录调用。配合 chat_service._last_summary_task 可在
    测试中 await 确定性断言后台 task 已完成。
    """

    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    async def apply(self, draft_id: int, payload: dict) -> None:
        self.calls.append({"draft_id": draft_id, "payload": payload})


class FakeKnowledgeRetriever:
    """检索 seam 的 fake：可控召回内容/空/异常，记录调用。"""

    def __init__(
        self,
        chunks: list[KnowledgeChunk] | None = None,
        *,
        raises: bool = False,
    ) -> None:
        self._chunks = chunks or []
        self._raises = raises
        self.calls: list[str] = []

    async def search(self, query: str) -> list[KnowledgeChunk]:
        self.calls.append(query)
        if self._raises:
            raise RuntimeError("检索失败（fake）")
        return list(self._chunks)


class FakeGraphTraverser:
    """图谱遍历 seam 的 fake：可控邻接结果/空/异常，记录调用实体。"""

    def __init__(
        self,
        neighbors: list[GraphNeighbor] | None = None,
        *,
        raises: bool = False,
    ) -> None:
        self._neighbors = neighbors or []
        self._raises = raises
        self.calls: list[list[str]] = []

    async def traverse(self, entities: list[str]) -> list[GraphNeighbor]:
        self.calls.append(list(entities))
        if self._raises:
            raise RuntimeError("图谱遍历失败（fake）")
        return list(self._neighbors)


@pytest.fixture
def harness() -> Iterator[SimpleNamespace]:
    fake_agent = FakeAgentRunner()
    fake_emotion = FakeEmotionJudge()
    fake_triage = FakeTriageJudge()
    fake_preconsult = FakePreconsultJudge()
    app = create_test_app(
        health_service=StubHealthService(),
        agent_runner=fake_agent,
        agent_auth_secret=TEST_AGENT_SECRET,
        emotion_judge=fake_emotion,
        triage_judge=fake_triage,
        preconsult_judge=fake_preconsult,
    )
    with TestClient(app) as client:
        yield SimpleNamespace(
            client=client,
            agent=fake_agent,
            emotion=fake_emotion,
            triage=fake_triage,
            preconsult=fake_preconsult,
        )
