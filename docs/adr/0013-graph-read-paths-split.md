# 图谱只读访问的双接口分离

Status: accepted

## 背景

票 13 落地医学知识图谱全量 seed 与 B 端可视化。Neo4j 存在两类只读消费方：Agent 层的 `traverse_graph` 工具（LLM 自主调用，入参实体列表，返回一跳邻接供导诊回答）与 B 端图谱可视化页（server-java 鉴权接口转调 server-py，返回全图节点+边供 AntV 力导向图渲染）。ADR-0010 只覆盖了知识源选择器与工具注入范式，未涉及可视化通路。

## 决策

server-py 暴露**两个分离的只读入口**，共用底层 Neo4j 只读 client，但接口形状与消费方各自独立：

1. **`traverse_graph` 工具**（`tools/`，给 LLM）：范式 1 的 `@tool`，LLM 自主调用，入参实体列表（含可选种子参数 `seed_entities` 预留），返回 `{entities, neighbors, summary, count}` 结构化文本。graph 态注入、none 态不注入，与 `search_knowledge` 互斥（同一请求只注入一个知识工具）。结果投影为 SSE `knowledge` 元事件 `{source:"graph", status, count}`，与 rag 对称。

2. **图谱投影 HTTP 接口**（`api/`，给 B 端）：server-java 鉴权 controller 转调 server-py 只读接口，返回最小拓扑骨架 `{nodes:[{id,label,group}], edges:[{source,target,type}]}`，不携带节点属性。节点详情点击时另取，不塞进投影。

## 被否决的方案

- **单接口复用**（让 `traverse_graph` 既给 LLM 用也给 B 端用，空种子返回全图）：LLM 工具语义（返回 LLM 易消费的结构化文本）与 REST 投影语义（返回 `{nodes,edges}` 图结构）数据形态不同，混在一个函数会拧巴；且 server-py 的 `api/` 与 `tools/` 是既有分层，HTTP 接口归 `api/`、工具归 `tools/`，分离符合分层护栏。

## 关联

- 不修订 ADR-0010（知识源选择器与工具注入范式已覆盖 graph 态）。
- 不修订 ADR-0006（边界"Neo4j 只读、server-java 转调 server-py"已就位，本决策补充其接口形状）。
- 写入管理（增删 RAG 文本 / 图谱节点）属后续扩展功能，需先修订 ADR-0006/0010 的只读约束，不在票 13 范围。
