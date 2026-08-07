"""推理档位映射（ADR-0015）。

C 端用户可选三档：自动 / 快速回答 / 深度思考。快速回答始终关闭思考，
深度思考始终使用 high；自动档普通对话关闭思考、复杂解读使用 high。
auto 永远不直接传给模型。
"""

from typing import Literal

EffortChoice = Literal["auto", "quick", "deep"]
# 场景枚举无法从契约 JSON 动态生成（Literal 需类定义期常量），
# 与 contracts/chat-defaults.json scenarios 的一致性由 tests/test_contract_consumption.py 钉死。
Scenario = Literal["triage", "interpretation", "preconsultation"]
ReasoningEffort = Literal["disabled", "high"]

# 自动档的场景分配表：预问诊是多轮对话收集，速度优先，关闭思考
_AUTO_BY_SCENARIO: dict[Scenario, ReasoningEffort] = {
    "triage": "disabled",
    "interpretation": "high",
    "preconsultation": "disabled",
}


def map_reasoning_effort(choice: EffortChoice, scenario: Scenario) -> ReasoningEffort:
    if choice == "quick":
        return "disabled"
    if choice == "deep":
        return "high"
    return _AUTO_BY_SCENARIO[scenario]
