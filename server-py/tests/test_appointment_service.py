import asyncio
from typing import Any

import httpx

from app.services.appointments import create_with_summary


class StubCallback:
    def __init__(self, responses: list[dict[str, Any] | Exception]) -> None:
        self.responses = iter(responses)
        self.calls: list[str] = []

    async def post(self, path: str, payload: dict[str, Any]) -> Any:
        self.calls.append(path)
        response = next(self.responses)
        if isinstance(response, Exception):
            raise response
        return response


def run_create(client: StubCallback) -> dict[str, Any]:
    return asyncio.run(create_with_summary(
        client,
        patient_id=12,
        conversation_id=7,
        schedule_id=9,
        condition_summary="主诉胸闷两天",
    ))


def test_existing_appointment_with_summary_is_returned_unchanged() -> None:
    existing = {"appointment_id": 21, "summary_sent": True, "notice": "病情摘要已发送给医生"}
    client = StubCallback([existing])

    assert run_create(client) == existing
    assert client.calls == ["/api/agent/appointments"]


def test_summary_failure_keeps_successful_appointment_card() -> None:
    client = StubCallback([
        {"appointment_id": 21, "summary_sent": False},
        httpx.ConnectError("server-java unavailable"),
    ])

    result = run_create(client)

    assert result["appointment_id"] == 21
    assert result["summary_sent"] is False
    assert result["notice"] == "挂号成功，病情摘要暂未发送"
