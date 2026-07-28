"""红线症状规则引擎测试（service 层 seam，票 04 明确要求的测试面）。

覆盖：危险输入必触发、普通症状不误触。
"""

import pytest

from app.services.red_flag import RedFlagService


@pytest.fixture
def service() -> RedFlagService:
    return RedFlagService()


@pytest.mark.parametrize(
    "text",
    [
        "我突然胸痛，还出冷汗",
        "胸口痛得厉害，大汗淋漓",
        "我现在意识模糊，说话不清楚",
        "老人突然昏迷叫不醒",
        "喘不上气，感觉呼吸困难",
        "刚才吐血了",
        "孩子全身抽搐停不下来",
        "误服了农药，现在很难受",
        "突然口角歪斜，半边身子动不了",
    ],
)
def test_dangerous_input_must_hit(service: RedFlagService, text: str) -> None:
    hit = service.judge(text)

    assert hit is not None
    assert hit.rule_name
    assert "120" in hit.advice


@pytest.mark.parametrize(
    "text",
    [
        "有点咳嗽，嗓子痒",
        "头疼两天了，不是很厉害",
        "感冒发烧38度，浑身没劲",
        "胃有点胀，消化不良",
        "最近睡眠不好，容易醒",
        "胸闷，不知道是不是天气原因",
        "胸口有点痛，就一下",
    ],
)
def test_normal_symptoms_must_not_hit(service: RedFlagService, text: str) -> None:
    assert service.judge(text) is None


def test_chest_pain_with_cold_sweat_names_the_rule(service: RedFlagService) -> None:
    hit = service.judge("胸口痛，还一直出冷汗")

    assert hit is not None
    assert "胸痛" in hit.rule_name
