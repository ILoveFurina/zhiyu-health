"""推理档位映射（ADR-0004）。

C 端用户可选三档：自动 / 快速回答 / 深度思考。后端映射为模型的
reasoning_effort 实参：快速回答→low，深度思考→high，自动档按场景分配
（导诊对话→low，报告解读等一次性长任务→high）。auto 永远不直接传给模型。
"""

from typing import Literal

EffortChoice = Literal["auto", "quick", "deep"]
Scenario = Literal["triage", "interpretation"]
ReasoningEffort = Literal["low", "high"]

# 自动档的场景分配表
_AUTO_BY_SCENARIO: dict[Scenario, ReasoningEffort] = {
    "triage": "low",
    "interpretation": "high",
}


def map_reasoning_effort(choice: EffortChoice, scenario: Scenario) -> ReasoningEffort:
    if choice == "quick":
        return "low"
    if choice == "deep":
        return "high"
    return _AUTO_BY_SCENARIO[scenario]
