"""知识图谱只读投影接口（ADR-0013 决策 2）。

供 server-java 鉴权后转调（B 端可视化页不直连 server-py 或 Neo4j）。
返回最小拓扑骨架 {nodes, edges}，节点详情点击时另取。
与 traverse_graph 工具分离：工具给 LLM（tools/），本接口给 B 端（api/）。
"""

from fastapi import APIRouter, HTTPException, Query, Request

from app.api.deps import AgentCallbackAuth

router = APIRouter(prefix="/knowledge", tags=["knowledge"])


@router.get("/graph")
async def graph_projection(request: Request, _: AgentCallbackAuth) -> dict:
    """返回全图最小拓扑骨架 {nodes, edges}（ADR-0013 决策 6）。

    投影器未配置时返回空图（降级展示），不抛错给 B 端。
    """
    projector = getattr(request.app.state, "graph_projector", None)
    if projector is None:
        return {"nodes": [], "edges": []}
    return await projector.projection()


@router.get("/graph/node")
async def graph_node_detail(
    request: Request,
    _: AgentCallbackAuth,
    node_id: str = Query(..., description="节点复合 id，形如 symptom:胸闷气短"),
) -> dict:
    """点击节点取详情：返回节点类型与全部属性（grilling 决策 6：属性不塞进投影）。"""
    projector = getattr(request.app.state, "graph_projector", None)
    if projector is None:
        raise HTTPException(status_code=503, detail="知识图谱投影不可用")
    detail = await projector.node_detail(node_id)
    if detail is None:
        raise HTTPException(status_code=404, detail="节点不存在")
    return detail
