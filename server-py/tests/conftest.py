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
