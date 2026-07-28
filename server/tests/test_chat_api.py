"""C 端对话 SSE（HTTP seam，fake Agent 替换 LLM）。

覆盖：SSE 事件序列、免责声明注入、消息持久化、多轮上下文、
红线命中中断导诊且不调用 Agent、推理档位后端映射。
"""

import json
from types import SimpleNamespace

from conftest import auth_headers, login


def _post_chat(client, token: str, payload: dict) -> list[dict]:
    with client.stream("POST", "/api/c/chat", json=payload, headers=auth_headers(token)) as response:
        assert response.status_code == 200
        raw = "".join(response.iter_text())
    events = []
    for frame in raw.split("\n\n"):
        if not frame.strip():
            continue
        lines = frame.strip().split("\n")
        event = lines[0].removeprefix("event: ")
        data = json.loads(lines[1].removeprefix("data: "))
        events.append({"event": event, "data": data})
    return events


def test_chat_streams_tokens_and_final_message_with_disclaimer(harness: SimpleNamespace) -> None:
    token = login(harness.client)

    events = _post_chat(harness.client, token, {"content": "最近总是咳嗽怎么办"})

    kinds = [e["event"] for e in events]
    assert kinds[0] == "meta"
    assert kinds[-1] == "done"
    assert kinds.count("token") == 3  # fake 的固定三段 token

    meta = events[0]["data"]
    assert meta["conversation_id"] > 0

    final = events[-2]
    assert final["event"] == "message"
    assert final["data"]["role"] == "assistant"
    assert final["data"]["content"] == "你好，我是小愈。"
    assert final["data"]["disclaimer"] == "仅供参考，不替代医生诊断"
    assert final["data"]["effort"] == "low"  # 自动档导诊场景映射 low


def test_chat_persists_user_and_assistant_messages(harness: SimpleNamespace) -> None:
    token = login(harness.client)
    events = _post_chat(harness.client, token, {"content": "我想咨询头疼的问题"})
    conversation_id = events[0]["data"]["conversation_id"]

    response = harness.client.get(
        f"/api/c/conversations/{conversation_id}/messages", headers=auth_headers(token)
    )

    assert response.status_code == 200
    messages = response.json()
    assert [m["role"] for m in messages] == ["user", "assistant"]
    assert messages[0]["content"] == "我想咨询头疼的问题"
    assert messages[0]["disclaimer"] is None
    assert messages[1]["content"] == "你好，我是小愈。"
    assert messages[1]["disclaimer"] == "仅供参考，不替代医生诊断"


def test_second_turn_carries_full_context(harness: SimpleNamespace) -> None:
    token = login(harness.client)
    first = _post_chat(harness.client, token, {"content": "我咳嗽三天了"})
    conversation_id = first[0]["data"]["conversation_id"]

    _post_chat(
        harness.client,
        token,
        {"content": "还开始发烧了", "conversation_id": conversation_id},
    )

    assert len(harness.agent.calls) == 2
    history = harness.agent.calls[1]["messages"]
    assert [(m["role"], m["content"]) for m in history] == [
        ("user", "我咳嗽三天了"),
        ("assistant", "你好，我是小愈。"),
        ("user", "还开始发烧了"),
    ]


def test_red_flag_interrupts_without_calling_agent(harness: SimpleNamespace) -> None:
    token = login(harness.client)

    events = _post_chat(harness.client, token, {"content": "我突然胸痛，还出冷汗"})

    kinds = [e["event"] for e in events]
    assert kinds == ["meta", "red_flag", "done"]
    assert harness.agent.calls == []  # 命中红线后不再调用导诊 Agent

    red_flag = events[1]["data"]
    assert "120" in red_flag["advice"]
    assert "胸痛" in red_flag["rule"]

    conversation_id = events[0]["data"]["conversation_id"]
    messages = harness.client.get(
        f"/api/c/conversations/{conversation_id}/messages", headers=auth_headers(token)
    ).json()
    assert [m["kind"] for m in messages] == ["text", "red_flag"]
    assert "120" in messages[1]["content"]


def test_effort_choice_is_mapped_by_backend(harness: SimpleNamespace) -> None:
    token = login(harness.client)

    for choice, expected in [("auto", "low"), ("quick", "low"), ("deep", "high")]:
        harness.agent.calls.clear()
        _post_chat(harness.client, token, {"content": "感冒吃什么药", "effort": choice})
        assert harness.agent.calls[0]["effort"] == expected
        assert harness.agent.calls[0]["effort"] != "auto"


def test_chat_with_unknown_conversation_returns_404(harness: SimpleNamespace) -> None:
    token = login(harness.client)

    response = harness.client.post(
        "/api/c/chat",
        json={"content": "你好", "conversation_id": 999},
        headers=auth_headers(token),
    )

    assert response.status_code == 404


def test_chat_requires_auth(harness: SimpleNamespace) -> None:
    response = harness.client.post("/api/c/chat", json={"content": "你好"})

    assert response.status_code == 401


def test_messages_of_other_patients_conversation_is_404(harness: SimpleNamespace) -> None:
    owner_token = login(harness.client, "患者甲")
    other_token = login(harness.client, "患者乙")
    events = _post_chat(harness.client, owner_token, {"content": "我胃疼"})
    conversation_id = events[0]["data"]["conversation_id"]

    read = harness.client.get(
        f"/api/c/conversations/{conversation_id}/messages", headers=auth_headers(other_token)
    )
    continue_chat = harness.client.post(
        "/api/c/chat",
        json={"content": "接着问", "conversation_id": conversation_id},
        headers=auth_headers(other_token),
    )

    assert read.status_code == 404
    assert continue_chat.status_code == 404
