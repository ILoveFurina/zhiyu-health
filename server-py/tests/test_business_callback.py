"""业务工具回调 seam：用 HTTPX MockTransport 替代真实业务后端。"""

import asyncio
import json

import httpx

from app.tools.business import BusinessCallbackClient


def test_business_callback_uses_fake_http_transport_in_order() -> None:
    calls: list[tuple[str, str, object]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content) if request.content else None
        calls.append((request.method, request.url.path, payload))
        return httpx.Response(200, json={"ok": True})

    async def run() -> None:
        client = BusinessCallbackClient(
            "http://server-java.test", transport=httpx.MockTransport(handler)
        )
        try:
            assert await client.get("/api/agent/slots", {"doctor_id": 2}) == {"ok": True}
            assert await client.post("/api/agent/appointments", {"schedule_id": 8}) == {"ok": True}
        finally:
            await client.aclose()

    asyncio.run(run())

    assert calls == [
        ("GET", "/api/agent/slots", None),
        ("POST", "/api/agent/appointments", {"schedule_id": 8}),
    ]
