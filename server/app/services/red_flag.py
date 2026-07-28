"""红线症状确定性规则引擎（硬规则：安全判断走规则不走 LLM）。

规则表与判断逻辑都在 service 层；规则判断必须先于 LLM/工具循环执行，
命中后立即中断导诊。每条规则由若干关键词组构成：组内任一近义词命中、
且所有组都命中时触发（组的设计用于表达"胸痛伴冷汗"这类组合症状）。
"""

from dataclasses import dataclass, field

RED_FLAG_ADVICE = "请立即就近就医，或拨打 120 急救电话"


@dataclass(frozen=True)
class RedFlagHit:
    rule_name: str
    advice: str


@dataclass(frozen=True)
class RedFlagRule:
    name: str
    groups: tuple[tuple[str, ...], ...]
    advice: str = field(default=RED_FLAG_ADVICE)


RED_FLAG_RULES: tuple[RedFlagRule, ...] = (
    RedFlagRule(
        name="胸痛伴冷汗（疑似心梗）",
        groups=(("胸痛", "胸口痛", "胸口疼"), ("冷汗", "出冷汗", "大汗淋漓")),
    ),
    RedFlagRule(
        name="意识障碍",
        groups=(("意识模糊", "昏迷", "失去意识", "昏厥", "叫不醒"),),
    ),
    RedFlagRule(
        name="呼吸窘迫",
        groups=(("呼吸困难", "喘不上气", "无法呼吸", "窒息"),),
    ),
    RedFlagRule(
        name="中风征兆",
        groups=(("口角歪斜", "半身不遂", "一侧肢体无力", "半边身子无力", "偏瘫"),),
    ),
    RedFlagRule(
        name="大出血/呕血咯血",
        groups=(("大出血", "呕血", "吐血", "咯血", "便血不止"),),
    ),
    RedFlagRule(
        name="持续抽搐",
        groups=(("抽搐不止", "持续抽搐", "全身抽搐", "抽搐停不下"),),
    ),
    RedFlagRule(
        name="急性中毒",
        groups=(("服毒", "农药中毒", "喝了农药", "服了农药", "误服农药", "煤气中毒", "一氧化碳中毒"),),
    ),
)


class RedFlagService:
    def __init__(self, rules: tuple[RedFlagRule, ...] = RED_FLAG_RULES) -> None:
        self._rules = rules

    def judge(self, text: str) -> RedFlagHit | None:
        """命中第一条匹配规则即返回；未命中返回 None。"""
        compact = "".join(text.split())
        for rule in self._rules:
            if all(any(synonym in compact for synonym in group) for group in rule.groups):
                return RedFlagHit(rule_name=rule.name, advice=rule.advice)
        return None
