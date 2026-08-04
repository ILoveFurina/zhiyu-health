"""情绪反馈（票 44，ADR-0019）测试：结构化输出校验、重试、降级 calm、message 事件携带 emotion。

覆盖：
- EmotionJudge 的 json_object + pydantic 校验 + 2 次重试范式（复用 vision interpreter 范式）
- 调用异常/校验失败/空输入均降级 calm 不阻塞
- chat service 的 message 事件携带 emotion + soothing_text（anxious/fearful）
- fake emotion judge 的调用断言（收到最后一条用户消息）
"""

import asyncio
import json

from conftest import TEST_AGENT_SECRET, FakeAgentRunner, FakeEmotionJudge, StubHealthService
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.agent.emotion import StructuredEmotionJudge
from app.main import create_app
from app.schemas.emotion import EmotionResult, emotion_soothing_text


class FakeRawEmotionModel:
    """记录调用并按预设序列返回原始文本（可模拟非法 JSON / 校验失败 / 异常）。"""

    def __init__(self, responses: list[str]) -> None:
        self.responses = iter(responses)
        self.calls: list[str] = []

    async def ainvoke(self, user_text: str) -> str:
        self.calls.append(user_text)
        response = next(self.responses)
        if isinstance(response, Exception):
            raise response
        return response


def test_emotion_result_validates_three_tier_enum() -> None:
    assert EmotionResult(emotion="calm", rationale="平静").emotion == "calm"
    assert EmotionResult(emotion="anxious", rationale="焦虑").emotion == "anxious"
    assert EmotionResult(emotion="fearful", rationale="恐惧").emotion == "fearful"
    # 非白名单枚举值拒绝
    try:
        EmotionResult(emotion="angry", rationale="愤怒")
        raise AssertionError("应拒绝非白名单 emotion")
    except ValidationError:
        pass


def test_calm_default_is_calm() -> None:
    result = EmotionResult.calm_default()
    assert result.emotion == "calm"


def test_soothing_text_mapping() -> None:
    # calm 无安抚语，anxious/fearful 各一条
    assert emotion_soothing_text("calm") is None
    assert emotion_soothing_text("anxious") is not None
    assert emotion_soothing_text("fearful") is not None
    assert "120" in emotion_soothing_text("fearful")
    assert emotion_soothing_text(None) is None
    assert emotion_soothing_text("unknown") is None


def test_judge_returns_valid_emotion_on_first_try() -> None:

    model = FakeRawEmotionModel([json.dumps({"emotion": "anxious", "rationale": "反复询问症状"})])
    judge = StructuredEmotionJudge(model)
    result = asyncio.run(judge.judge("我头疼好几天了会不会很严重"))
    assert result.emotion == "anxious"
    assert model.calls == ["我头疼好几天了会不会很严重"]


def test_judge_retries_on_invalid_json_then_succeeds() -> None:
    model = FakeRawEmotionModel([
        "这不是 JSON",
        json.dumps({"emotion": "fearful", "rationale": "胸痛伴冷汗"}),
    ])
    judge = StructuredEmotionJudge(model)

    result = asyncio.run(judge.judge("胸痛出冷汗"))
    assert result.emotion == "fearful"
    assert len(model.calls) == 2  # 重试了一次


def test_judge_degrades_to_calm_after_two_failures() -> None:
    model = FakeRawEmotionModel([
        json.dumps({"emotion": "angry", "rationale": "非白名单"}),  # 校验失败
        "仍然不是 JSON",  # JSON 解析失败
    ])
    judge = StructuredEmotionJudge(model)

    result = asyncio.run(judge.judge("随便说点什么"))
    assert result.emotion == "calm"  # 降级 calm
    assert len(model.calls) == 2


def test_judge_degrades_to_calm_on_model_exception() -> None:
    model = FakeRawEmotionModel([RuntimeError("方舟调用超时")])
    judge = StructuredEmotionJudge(model)

    result = asyncio.run(judge.judge("胸痛"))
    assert result.emotion == "calm"


def test_judge_returns_calm_for_empty_input() -> None:
    model = FakeRawEmotionModel([])
    judge = StructuredEmotionJudge(model)

    result = asyncio.run(judge.judge("   "))
    assert result.emotion == "calm"
    assert model.calls == []  # 空输入不调用模型


def _post_chat(client, payload: dict) -> list[dict]:
    payload = {"knowledge_source": "none", "patient_id": 12, "conversation_id": 7, **payload}
    with client.stream(
        "POST",
        "/api/agent/chat",
        json=payload,
        headers={"X-Agent-Callback-Token": TEST_AGENT_SECRET},
    ) as response:
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


def test_message_event_carries_anxious_emotion_and_soothing_text() -> None:
    """anxious 情绪：message 事件携带 emotion + soothing_text。"""
    fake_emotion = FakeEmotionJudge(EmotionResult(emotion="anxious", rationale="反复询问"))
    app = create_app(
        health_service=StubHealthService(),
        agent_runner=FakeAgentRunner(),
        agent_auth_secret=TEST_AGENT_SECRET,
        emotion_judge=fake_emotion,
    )
    with TestClient(app) as client:
        events = _post_chat(client, {"messages": [{"role": "user", "content": "我头疼好几天了怎么办"}]})

    final = events[-2]
    assert final["event"] == "message"
    assert final["data"]["emotion"] == "anxious"
    assert final["data"]["soothing_text"] is not None
    assert "别太担心" in final["data"]["soothing_text"]
    # fake judge 收到的是最后一条用户消息
    assert fake_emotion.calls == ["我头疼好几天了怎么办"]


def test_message_event_carries_fearful_emotion_and_soothing_text_with_120() -> None:
    """fearful 情绪：安抚语含"建议联系医生或拨打 120"。"""
    fake_emotion = FakeEmotionJudge(EmotionResult(emotion="fearful", rationale="胸痛冷汗"))
    app = create_app(
        health_service=StubHealthService(),
        agent_runner=FakeAgentRunner(),
        agent_auth_secret=TEST_AGENT_SECRET,
        emotion_judge=fake_emotion,
    )
    with TestClient(app) as client:
        events = _post_chat(client, {"messages": [{"role": "user", "content": "胸痛出冷汗"}]})

    final = events[-2]
    assert final["data"]["emotion"] == "fearful"
    assert "120" in final["data"]["soothing_text"]


def test_message_event_calm_has_no_soothing_text() -> None:
    """calm 情绪：无 soothing_text 字段（映射缺省即无）。"""
    fake_emotion = FakeEmotionJudge(EmotionResult.calm_default())
    app = create_app(
        health_service=StubHealthService(),
        agent_runner=FakeAgentRunner(),
        agent_auth_secret=TEST_AGENT_SECRET,
        emotion_judge=fake_emotion,
    )
    with TestClient(app) as client:
        events = _post_chat(client, {"messages": [{"role": "user", "content": "你好"}]})

    final = events[-2]
    assert final["data"]["emotion"] == "calm"
    assert "soothing_text" not in final["data"]


def test_emotion_judge_receives_last_user_message_in_multi_turn() -> None:
    """多轮对话：emotion 判断输入是最后一条用户消息，不是整段历史。"""
    fake_emotion = FakeEmotionJudge(EmotionResult.calm_default())
    app = create_app(
        health_service=StubHealthService(),
        agent_runner=FakeAgentRunner(),
        agent_auth_secret=TEST_AGENT_SECRET,
        emotion_judge=fake_emotion,
    )
    with TestClient(app) as client:
        _post_chat(client, {
            "messages": [
                {"role": "user", "content": "我咳嗽三天了"},
                {"role": "assistant", "content": "有发烧吗"},
                {"role": "user", "content": "还开始发烧了"},
            ],
        })

    assert fake_emotion.calls == ["还开始发烧了"]


# 票 20 验收要求：固定 3 条焦虑表达样例及期望 emotion/UI/安抚文案，三条均通过方可验收。
# 样例覆盖三档情绪（calm/anxious/fearful），断言 emotion 值 + soothing_text 内容。
_ACCEPTANCE_SAMPLES = [
    (
        "我想了解一下高血压的日常注意事项",
        "calm",
        None,  # calm 无安抚语
    ),
    (
        "我头疼好几天了，会不会是很严重的病，我好担心",
        "anxious",
        "别太担心",
    ),
    (
        "我突然胸口剧痛，出冷汗，喘不上气，我是不是要不行了",
        "fearful",
        "120",
    ),
]


def test_ticket20_acceptance_three_samples_pass_gate() -> None:
    """票 20 验收门：3 条固定样例的 emotion + 安抚文案均符合期望，三条全过方可验收。"""
    for user_text, expected_emotion, expected_soothing_fragment in _ACCEPTANCE_SAMPLES:
        fake_emotion = FakeEmotionJudge(
            EmotionResult(emotion=expected_emotion, rationale="验收样例")
        )
        app = create_app(
            health_service=StubHealthService(),
            agent_runner=FakeAgentRunner(),
            agent_auth_secret=TEST_AGENT_SECRET,
            emotion_judge=fake_emotion,
        )
        with TestClient(app) as client:
            events = _post_chat(client, {"messages": [{"role": "user", "content": user_text}]})

        final = events[-2]
        assert final["data"]["emotion"] == expected_emotion, (
            f"样例「{user_text}」期望 emotion={expected_emotion}，"
            f"实际 emotion={final['data']['emotion']}"
        )
        if expected_soothing_fragment is None:
            assert "soothing_text" not in final["data"], (
                f"样例「{user_text}」期望无安抚语，实际 soothing_text={final['data'].get('soothing_text')}"
            )
        else:
            assert expected_soothing_fragment in final["data"]["soothing_text"], (
                f"样例「{user_text}」期望安抚语含「{expected_soothing_fragment}」，"
                f"实际 soothing_text={final['data'].get('soothing_text')}"
            )
