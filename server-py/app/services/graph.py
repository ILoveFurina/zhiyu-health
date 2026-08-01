"""知识图谱遍历 service（ADR-0013）：Neo4j 只读一跳扩展 + 邻接结果格式化。

server-py 运行时对 Neo4j 只读（沿边一跳扩展，GraphRAG 雏形），不写图谱。
遍历失败或空召回静默降级返回空列表，由调用方走裸 LLM（与 RAG 降级纪律对称）。
邻接结果以 GraphNeighbor 结构化形式进入 LLM 上下文，衔接疾病/科室/药品推荐。

GraphNeighbor/GraphTraverser 类型定义在 tools/graph.py（分层护栏：
tools 不得 import services，故类型归 tools，本模块反向 import 复用）。

实体名对齐（grilling 决策 3）：seed 的 Symptom/Disease 节点带 aliases 属性，
Cypher 用 WHERE n.name = $entity OR $entity IN n.aliases 匹配，把"叫法不同"
这个最常见空召回源在 seed 层消解，不引入运行时模糊匹配。
"""

from neo4j import AsyncDriver
from neo4j.exceptions import Neo4jError

from app.db.clients import KnowledgeClients
from app.tools.graph import GraphNeighbor, GraphTraverser

__all__ = [
    "GraphNeighbor",
    "GraphTraverser",
    "Neo4jGraphTraverser",
    "Neo4jGraphProjector",
    "build_graph_traverser",
    "build_graph_projector",
]

# 一跳扩展 Cypher：从所有五类节点中按实体名（含别名）匹配种子节点，
# 再沿出边与入边各扩展一跳，返回邻接节点 + 关系类型 + 方向。
# UNWIND entities 后逐实体匹配，避免大 IN 子句的索引退化。
_TRAVERSE_CYPHER = """
UNWIND $entities AS entity
MATCH (n)
WHERE (n:Symptom OR n:Disease OR n:Department OR n:Medication OR n:Contraindication)
  AND (n.name = entity OR entity IN n.aliases)
OPTIONAL MATCH (n)-[out_rel]->(neighbor)
WHERE neighbor IS NOT NULL
OPTIONAL MATCH (n)<-[in_rel]-(in_neighbor)
WHERE in_neighbor IS NOT NULL
RETURN neighbor.name AS neighbor_name,
       labels(neighbor)[0] AS neighbor_type,
       type(out_rel) AS out_relation,
       in_neighbor.name AS in_neighbor_name,
       labels(in_neighbor)[0] AS in_neighbor_type,
       type(in_rel) AS in_relation
"""

# 图谱投影 Cypher（ADR-0013 决策 6）：返回最小拓扑骨架，不携带节点属性。
# 节点 id 用 node_id 复合形式（{label_type}:{natural_key}），group 取 label 名。
# 只投影五类知识节点，业务实体零双写（ADR-0006）。
_PROJECTION_CYPHER = """
MATCH (n)
WHERE n:Symptom OR n:Disease OR n:Department OR n:Medication OR n:Contraindication
WITH collect(n) AS nodes
UNWIND nodes AS n
MATCH (n)-[r]->(m)
WHERE m:Symptom OR m:Disease OR m:Department OR m:Medication OR m:Contraindication
RETURN n.node_id AS source, type(r) AS type, m.node_id AS target, labels(n)[0] AS source_group,
       n.name AS source_name, labels(m)[0] AS target_group, m.name AS target_name
"""

# 节点详情 Cypher：点击节点时另取属性（grilling 决策 6：属性不塞进投影）。
_DETAIL_CYPHER = """
MATCH (n)
WHERE n.node_id = $node_id
RETURN n.node_id AS node_id, labels(n)[0] AS node_type, n.name AS name,
       n.aliases AS aliases, n.description AS description,
       n.ingredients AS ingredients, n.allergen AS allergen,
       n.department AS department, n.name_snapshot AS name_snapshot,
       n.medication_id AS medication_id
"""


async def _read(driver: AsyncDriver, cypher: str, **params) -> list[dict] | None:
    """只读 Cypher 执行的共享辅助：开 READ 会话跑查询，返回 records 或 None（异常降级）。

    把 try/except + session 生命周期管理收拢在此，避免 traverse/projection/node_detail
    三处复制粘贴同样的降级逻辑。
    """
    try:
        async with driver.session(default_access_mode="READ") as session:
            result = await session.run(cypher, **params)
            return await result.data()
    except (Neo4jError, OSError):
        return None


class Neo4jGraphTraverser:
    """生产实现：Neo4j 异步驱动 + 一跳 Cypher 扩展。

    复用 KnowledgeClients.neo4j 同一驱动（ADR-0013 决策：共用底层只读 client）。
    任何环节失败（连接、Cypher 执行）均静默降级返回空列表，不向用户暴露错误。
    """

    def __init__(self, driver: AsyncDriver) -> None:
        self._driver = driver

    async def traverse(self, entities: list[str]) -> list[GraphNeighbor]:
        if not entities:
            return []
        records = await _read(self._driver, _TRAVERSE_CYPHER, entities=entities)
        if records is None:
            return []
        neighbors: list[GraphNeighbor] = []
        for record in records:
            # 出边邻接：种子节点 -> 邻接节点
            if record.get("neighbor_name"):
                neighbors.append(GraphNeighbor(
                    name=record["neighbor_name"],
                    node_type=record["neighbor_type"],
                    relation=record["out_relation"],
                    direction="outgoing",
                ))
            # 入边邻接：邻接节点 -> 种子节点
            if record.get("in_neighbor_name"):
                neighbors.append(GraphNeighbor(
                    name=record["in_neighbor_name"],
                    node_type=record["in_neighbor_type"],
                    relation=record["in_relation"],
                    direction="incoming",
                ))
        return neighbors


def build_graph_traverser(clients: KnowledgeClients | None) -> GraphTraverser | None:
    """生产装配：Neo4j 驱动未配置或 clients 缺失时返回 None（遍历降级）。

    与 build_knowledge_retriever 对称：返回 None 即告知 runner/main 图谱不可用，
    选择器将 graph 态降级走裸 LLM 并发 unavailable knowledge 元事件。
    """
    if clients is None:
        return None
    return Neo4jGraphTraverser(driver=clients.neo4j)


class Neo4jGraphProjector:
    """图谱投影 service（ADR-0013 决策 2）：B 端可视化只读入口。

    返回最小拓扑骨架 {nodes, edges}，不携带节点属性；节点详情点击时另取。
    复用 KnowledgeClients.neo4j 同一驱动（与 traverser 共用只读 client）。
    任何环节失败返回空投影，不向 B 端暴露错误（降级展示空图）。
    """

    def __init__(self, driver: AsyncDriver) -> None:
        self._driver = driver

    async def projection(self) -> dict[str, list]:
        """返回全图最小拓扑骨架 {nodes:[{id,label,group}], edges:[{source,target,type}]}。"""
        records = await _read(self._driver, _PROJECTION_CYPHER)
        if records is None:
            return {"nodes": [], "edges": []}
        nodes: dict[str, dict[str, str]] = {}
        edges: list[dict[str, str]] = []
        for record in records:
            source_id = record.get("source")
            target_id = record.get("target")
            for node_id, name_key, group_key in [
                (source_id, "source_name", "source_group"),
                (target_id, "target_name", "target_group"),
            ]:
                if node_id and node_id not in nodes:
                    nodes[node_id] = {
                        "id": node_id,
                        "label": record.get(name_key, node_id),
                        "group": record.get(group_key, ""),
                    }
            if source_id and target_id:
                edges.append({
                    "source": source_id,
                    "target": target_id,
                    "type": record.get("type", ""),
                })
        return {"nodes": list(nodes.values()), "edges": edges}

    async def node_detail(self, node_id: str) -> dict[str, object] | None:
        """点击节点取详情：返回节点类型与全部属性（aliases/description/ingredients 等）。"""
        records = await _read(self._driver, _DETAIL_CYPHER, node_id=node_id)
        if not records:
            return None
        record = records[0]
        return {k: v for k, v in record.items() if v is not None}


def build_graph_projector(clients: KnowledgeClients | None) -> Neo4jGraphProjector | None:
    """生产装配：clients 缺失时返回 None（投影不可用，B 端展示空图）。"""
    if clients is None:
        return None
    return Neo4jGraphProjector(driver=clients.neo4j)
