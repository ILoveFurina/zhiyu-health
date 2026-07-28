"""测试基座：临时 SQLite 库（与 B 端测试同模式）+ fake Agent。

seam 纪律：主 seam 为 FastAPI HTTP API 层；LLM 以 FakeAgentRunner 替换，
断言它对多轮上下文与推理档位的接收情况以及业务副作用（消息落库）。
"""

from collections.abc import AsyncIterator, Iterator
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine

from app.main import create_app


class StubHealthService:
    async def check(self) -> dict[str, object]:
        return {"status": "ok", "services": {}}


class FakeAgentRunner:
    """LLM seam 的 fake：记录收到的消息历史与推理档位，回固定 token 流。"""

    def __init__(self, tokens: list[str] | None = None) -> None:
        self.tokens = tokens or ["你好", "，我是小愈", "。"]
        self.calls: list[dict[str, object]] = []

    async def astream_reply(
        self, messages: list[dict[str, str]], effort: str
    ) -> AsyncIterator[str]:
        self.calls.append({"messages": messages, "effort": effort})
        for token in self.tokens:
            yield token


@pytest.fixture
def harness(tmp_path: Path) -> Iterator[SimpleNamespace]:
    fake_agent = FakeAgentRunner()
    engine = create_engine(
        f"sqlite:///{tmp_path / 'chat.db'}", connect_args={"check_same_thread": False}
    )
    app = create_app(
        health_service=StubHealthService(),
        database_engine=engine,
        jwt_secret="test-secret-test-secret-test-secret",
        agent_runner=fake_agent,
    )
    with TestClient(app) as client:
        yield SimpleNamespace(client=client, agent=fake_agent, engine=engine)


def login(client: TestClient, nickname: str = "演示患者") -> str:
    response = client.post("/api/c/auth/mock-login", json={"nickname": nickname})
    assert response.status_code == 200
    return response.json()["token"]  # type: ignore[no-any-return]


def auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}
