"""Agent 对话 SSE（HTTP seam，fake Agent 替换 LLM）。

覆盖：SSE 事件序列、免责声明注入、消息历史透传、推理档位映射（auto 不外传）。
"""

import json
from types import SimpleNamespace


def _post_chat(client, payload: dict) -> list[dict]:
    with client.stream("POST", "/api/agent/chat", json=payload) as response:
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
    events = _post_chat(
        harness.client, {"messages": [{"role": "user", "content": "最近总是咳嗽怎么办"}]}
    )

    kinds = [e["event"] for e in events]
    assert kinds[0] == "meta"
    assert kinds[-1] == "done"
    assert kinds.count("token") == 3  # fake 的固定三段 token

    final = events[-2]
    assert final["event"] == "message"
    assert final["data"]["role"] == "assistant"
    assert final["data"]["content"] == "你好，我是小愈。"
    assert final["data"]["disclaimer"] == "仅供参考，不替代医生诊断"
    assert final["data"]["effort"] == "low"  # 自动档导诊场景映射 low


def test_message_history_is_forwarded_to_agent(harness: SimpleNamespace) -> None:
    _post_chat(
        harness.client,
        {
            "messages": [
                {"role": "user", "content": "我咳嗽三天了"},
                {"role": "assistant", "content": "有发烧吗"},
                {"role": "user", "content": "还开始发烧了"},
            ]
        },
    )

    history = harness.agent.calls[0]["messages"]
    assert [(m["role"], m["content"]) for m in history] == [
        ("user", "我咳嗽三天了"),
        ("assistant", "有发烧吗"),
        ("user", "还开始发烧了"),
    ]


def test_effort_choice_is_mapped_by_backend(harness: SimpleNamespace) -> None:
    for choice, expected in [("auto", "low"), ("quick", "low"), ("deep", "high")]:
        harness.agent.calls.clear()
        _post_chat(
            harness.client,
            {"messages": [{"role": "user", "content": "感冒吃什么药"}], "effort": choice},
        )
        assert harness.agent.calls[0]["effort"] == expected
        assert harness.agent.calls[0]["effort"] != "auto"


def test_scenario_drives_auto_effort(harness: SimpleNamespace) -> None:
    _post_chat(
        harness.client,
        {
            "messages": [{"role": "user", "content": "帮我解读这份报告"}],
            "scenario": "interpretation",
        },
    )
    assert harness.agent.calls[0]["effort"] == "high"  # 自动档解读场景映射 high


def test_empty_messages_is_rejected(harness: SimpleNamespace) -> None:
    response = harness.client.post("/api/agent/chat", json={"messages": []})

    assert response.status_code == 422
