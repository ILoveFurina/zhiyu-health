"""对话知识源选择与降级状态。

场景默认值和允许值来自跨栈契约。rag/graph 只有在对应只读能力已装配时才进入 runner；
未配置或空召回都允许继续裸 LLM，其中“未配置”的降级事件由本模块在 runner 前产生。
"""

from app.core.contracts import get_contracts
from app.services.reasoning import Scenario


class KnowledgeSourcePolicy:
    def __init__(self, *, rag_available: bool, graph_available: bool) -> None:
        self._rag_available = rag_available
        self._graph_available = graph_available
        self._contract = get_contracts().knowledge

    def resolve(self, requested: str | None, scenario: Scenario) -> str:
        source = requested or self._contract.default_by_scenario[scenario]
        if source not in self._contract.knowledge_sources:
            return self._contract.none_source
        if source == "rag" and not self._rag_available:
            return self._contract.none_source
        if source == "graph" and not self._graph_available:
            return self._contract.none_source
        return source

    def degraded_event(self, requested: str | None, scenario: Scenario) -> dict[str, object] | None:
        source = requested or self._contract.default_by_scenario[scenario]
        if source == "graph" and not self._graph_available:
            return {"source": "graph", "status": "unavailable", "count": 0}
        if source == "rag" and not self._rag_available:
            return {"source": "rag", "status": "degraded", "count": 0}
        return None
