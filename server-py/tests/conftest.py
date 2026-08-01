"""测试基座：fake Agent 替换 LLM。

seam 纪律：主 seam 为 FastAPI HTTP API 层；LLM 以 FakeAgentRunner 替换，
断言它对消息历史与推理档位的接收情况。
"""

from collections.abc import AsyncIterator, Iterator
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.agent.runner import AgentContext, AgentOutput
from app.main import create_app
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
    app = create_app(
        health_service=StubHealthService(),
        agent_runner=fake_agent,
        agent_auth_secret=TEST_AGENT_SECRET,
    )
    with TestClient(app) as client:
        yield SimpleNamespace(client=client, agent=fake_agent)
