"""知识图谱遍历工具（ADR-0013）：traverse_graph 作为 @tool 注入 Agent 导诊流程。

工具范式（范式 1，与 search_knowledge 对称）：LLM 自主调用，入参实体列表，
返回一跳邻接供导诊回答。graph 态注入本工具、none 态不注入；与 search_knowledge
互斥（同一请求工具集只有一个知识工具，grilling 决策 2）。

分层护栏：tools 不得直接 import app.db/app.services。GraphNeighbor 邻接结果
类型定义在此（services.graph 反向 import 它，复用同一类型）；遍历器经
runner 构造期注入（与 build_knowledge_tool 注入 KnowledgeRetriever 同模式）。
"""

from dataclasses import dataclass
from typing import Any, Protocol

from langchain_core.tools import BaseTool, tool


@dataclass(frozen=True)
class GraphNeighbor:
    """一跳邻接节点：节点名、类型（label）、到种子实体的关系类型与方向。

    neighbors 保留结构化明细供 LLM 消费；summary 另给自然语言串（grilling 决策 8）。
    """

    name: str
    node_type: str  # Symptom / Disease / Department / Medication / Contraindication
    relation: str  # INDICATES / TREATED_BY / SUGGESTS_DEPARTMENT / TREATS / ...
    direction: str  # outgoing / incoming


class GraphTraverser(Protocol):
    """遍历器最小接口；services.graph.Neo4jGraphTraverser 实现它。"""

    async def traverse(self, entities: list[str]) -> list[GraphNeighbor]: ...


def _summarize(entities: list[str], neighbors: list[GraphNeighbor]) -> str:
    """把邻接结果汇总成 LLM 即用的自然语言串（grilling 决策 8 的 summary）。"""
    if not neighbors:
        return "未在知识图谱中检索到与给定实体相关的邻接信息。"
    entities_str = "、".join(entities)
    # 按 node_type 分组计数，给 LLM 一个概览
    groups: dict[str, list[str]] = {}
    for n in neighbors:
        groups.setdefault(n.node_type, []).append(n.name)
    parts = [f"知识图谱中与 {entities_str} 相关的邻接节点："]
    for node_type, names in groups.items():
        parts.append(f"- {node_type}：{', '.join(names)}")
    return "\n".join(parts)


def build_graph_tool(traverser: GraphTraverser) -> list[BaseTool]:
    """装配 traverse_graph 工具；遍历器构造期注入，运行期由 LLM 自主调用。

    返回 list 以与 build_knowledge_tool 签名对齐，便于 runner 统一拼装工具集。
    """

    @tool
    async def traverse_graph(
        entities: list[str],
        seed_entities: list[str] | None = None,
    ) -> dict[str, Any]:
        """遍历医学知识图谱，按给定实体名做一跳扩展，检索关联的疾病、科室、药品与禁忌信息。

        用于导诊回答前检索症状-疾病-科室关联知识，使回答更准确、可衔接科室推荐。
        检索失败或无相关内容时返回空，由你基于自身知识回答。

        Args:
            entities: 实体名列表（症状/疾病/药品等），按名称或别名匹配图谱节点。
            seed_entities: 预留参数，当前未使用，为未来 RAG+图谱融合管道留口子（grilling 决策 1）。
        """
        neighbors = await traverser.traverse(entities)
        # 去重：同一邻接节点可能经多条路径或多个实体命中（grilling 决策 8：count=命中邻接节点数）
        seen: set[tuple[str, str, str, str]] = set()
        unique: list[GraphNeighbor] = []
        for n in neighbors:
            key = (n.name, n.node_type, n.relation, n.direction)
            if key not in seen:
                seen.add(key)
                unique.append(n)
        return {
            "entities": entities,
            "neighbors": [
                {
                    "name": n.name,
                    "node_type": n.node_type,
                    "relation": n.relation,
                    "direction": n.direction,
                }
                for n in unique
            ],
            "summary": _summarize(entities, unique),
            "count": len(unique),
        }

    return [traverse_graph]
