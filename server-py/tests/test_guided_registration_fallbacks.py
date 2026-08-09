"""标准科室目录适配器与普通对话不前置查询的回归测试。"""

import asyncio

import httpx

from app.agent.types import AgentOutput
from app.services.chat import AgentChatService
from app.tools.callback import BusinessCallbackClient
from app.tools.department import CallbackDepartmentDirectory


class FakeAgent:
    def __init__(self) -> None:
        self.calls = 0

    async def astream_reply(self, messages, effort, context):
        self.calls += 1
        yield AgentOutput("token", "你好")


class CountingDirectory:
    def __init__(self) -> None:
        self.list_calls = 0
        self.slot_calls = 0

    async def list_departments(self, longitude, latitude):
        self.list_calls += 1
        return []

    async def get_slots(self, department_id, longitude, latitude):
        self.slot_calls += 1
        return "unexpected"


def test_normal_quick_chat_skips_department_directory() -> None:
    async def run():
        agent = FakeAgent()
        directory = CountingDirectory()
        service = AgentChatService(agent, directory=directory)
        events = [
            event
            async for event in service.stream(
                messages=[{"role": "user", "content": "你好"}],
                patient_id=12,
                conversation_id=7,
                effort_choice="quick",
                scenario="triage",
                knowledge_source="none",
            )
        ]
        return agent, directory, events

    agent, directory, events = asyncio.run(run())
    assert agent.calls == 1
    assert directory.list_calls == 0
    assert directory.slot_calls == 0
    assert [event["event"] for event in events] == ["meta", "token", "message", "done"]


def _directory_response(payload, status: int = 200):
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(status, json=payload)

    callback = BusinessCallbackClient(
        "http://server-java.test",
        transport=httpx.MockTransport(handler),
        callback_secret="shared-secret",
    )
    return CallbackDepartmentDirectory(callback), callback, requests


def test_directory_accepts_wrapped_list() -> None:
    directory, callback, _ = _directory_response(
        {"departments": [{"id": 5, "name": "皮肤科"}, {"bad": True}]}
    )
    try:
        result = asyncio.run(directory.list_departments(None, None))
    finally:
        asyncio.run(callback.aclose())
    assert result == [{"id": 5, "name": "皮肤科"}]


def test_directory_accepts_bare_list() -> None:
    directory, callback, _ = _directory_response([{"id": 5, "name": "皮肤科"}])
    try:
        result = asyncio.run(directory.list_departments(None, None))
    finally:
        asyncio.run(callback.aclose())
    assert result == [{"id": 5, "name": "皮肤科"}]


def test_directory_rejects_invalid_shape() -> None:
    directory, callback, _ = _directory_response({"departments": "invalid"})
    try:
        result = asyncio.run(directory.list_departments(None, None))
    finally:
        asyncio.run(callback.aclose())
    assert result == "查询标准科室失败：业务后端返回格式异常"


def test_directory_failure_becomes_safe_text() -> None:
    directory, callback, _ = _directory_response({"detail": "down"}, status=503)
    try:
        result = asyncio.run(directory.list_departments(None, None))
    finally:
        asyncio.run(callback.aclose())
    assert isinstance(result, str)
    assert result.startswith("查询标准科室失败")


def test_directory_forwards_location_to_catalog() -> None:
    directory, callback, requests = _directory_response({"departments": []})
    try:
        asyncio.run(directory.list_departments(113.62, 34.75))
    finally:
        asyncio.run(callback.aclose())
    assert requests[0].url.params["longitude"] == "113.62"
    assert requests[0].url.params["latitude"] == "34.75"


def test_directory_forwards_location_to_slots() -> None:
    directory, callback, requests = _directory_response({"standard_department": {}, "doctors": []})
    try:
        asyncio.run(directory.get_slots(5, 113.62, 34.75))
    finally:
        asyncio.run(callback.aclose())
    assert requests[0].url.path == "/api/agent/standard-departments/5/slots"
    assert requests[0].url.params["latitude"] == "34.75"
