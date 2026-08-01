# 13 — 医学知识图谱全量与可视化

**What to build:** Neo4j 图谱五类节点（症状/疾病/科室/药品/禁忌）数据全量 seed；server-py 运行时只读 Neo4j，`traverse_graph` 工具接入 Agent（检索时沿边一跳扩展，GraphRAG 雏形）；B 端“医学知识图谱”页通过 server-java 对外入口读取图谱投影，以 AntV 力导向图渲染并支持点击节点看详情。

**Blocked by:** 29 — 票 02 业务迁移（Java）；10 — RAG 知识库

**Status:** done

- [x] 图谱 seed 与知识库场景同源，至少 150 个节点、200 条关系边；seed 属离线初始化，运行时 server-py 不写 Neo4j
- [x] server-py 只读知识 service + `traverse_graph` 工具接入 Agent；TestClient 用 fake 图客户端覆盖一跳扩展与关闭增强时不查询
- [x] server-java 提供已鉴权的 B 端只读图谱查询入口并转调 server-py 内部知识接口；不得让 admin 浏览器直连 server-py 或 Neo4j
- [x] B 端图谱可视化页使用项目约定的 AntV graph，节点点击展示详情；浏览器实测无控制台错误
- [x] 架构守卫：图谱只装知识，业务实体零双写（ADR-0006）
- [x] 图谱增强决定值从 `contracts/` 推导，并与票 10 的知识增强契约联动；现场切换 UI 留给票 25

## Comments

- 2026-07-29：按当前技术栈将旧 ECharts 描述改为 AntV，并明确 B 端只能经 server-java 访问、server-py 对 Neo4j 运行时只读。

## Grilling 决策记录

1. **知识源语义=互斥三态（rag/graph/none）**：rag 与 graph 互斥，加裸 LLM 共三态。否决"RAG+图谱双层叠加"管道（与 ADR-0006 第 14 行已否决的双层 GraphRAG 一致）。`traverse_graph` 工具签名预留可选 `seed_entities` 参数，为未来叠加态留口子，但融合管道不进票 13 acceptance。`knowledge_sources: ["rag","graph"]` 契约不改。
2. **工具范式 A1 互斥注入**：graph 态只注入 `traverse_graph`、不注入 `search_knowledge`；rag 态反之；none 都不注入。同一请求工具集永远只有一个知识工具，避免 LLM 双调。触发条件与 `search_knowledge` 对称（system prompt 引导"当你能调用 X 时先检索"）。`traverse_graph` 入参用实体列表。
3. **graph 空召回照搬 RAG 降级**：空召回标 `degraded` 走裸 LLM，不引入运行时模糊匹配/回退到 RAG。实体名对齐靠 seed 的 `aliases` 属性 + Cypher 别名匹配（`WHERE s.name = $entity OR $entity IN s.aliases`），把"实体名对不上"这个最常见空召回源在 seed 层消解。
4. **验收边界 C1**：票 13 交付 `traverse_graph` 工具 + 选择器解除 graph 降级 + server-java 图谱只读入口 + B 端可视化页。验收靠 TestClient + fake 图客户端断言工具调用与降级，以及 B 端页面浏览器实测无控制台错误。端到端真实对话走通 graph 留票 25（现场切换 UI 出口）。
5. **图谱只读访问双接口分离（ADR-0013）**：`traverse_graph` 工具（给 LLM，`tools/`）与图谱投影 HTTP 接口（给 B 端可视化，`api/`）分离，共用底层 Neo4j 只读 client。否决单接口复用。
6. **图谱投影结构 A（最小拓扑骨架）**：投影接口返回 `{nodes:[{id,label,group}], edges:[{source,target,type}]}`，节点 id 用 `{label_type}:{natural_key}` 复合形式。节点属性（ingredients/allergen/aliases 等）不塞进投影，点击详情时另取。前端用 `@antv/g6` v5 力导向图渲染，按 `group` 着色五类节点、按 `type` 区分边。
7. **图谱 seed 引入疾病节点（I1）**：构建"症状->疾病->科室"三元链补足节点/边数量。50 症状（对齐知识库标题）+ 10 科室（对齐知识库 department）+ ~70 疾病（每个症状关联 1-3 个常见疾病，规范名 + aliases）+ 30 药品 + 9 禁忌 ≈ 169 节点（过 150）；症状->疾病 + 疾病->科室 + 症状->科室 + 已有禁忌/相互作用 ≈ 257 边（过 200）。疾病数据 LLM 辅助生成 + 人工校对，校对列入待人工验收项。
8. **`traverse_graph` 返回结构 J1**：返回 `{entities, neighbors, summary, count}`，`summary` 给 LLM 即用的自然语言串，`neighbors` 保留结构化明细。knowledge 元事件 `{source:"graph", status, count}` 与 rag 对称，count 语义为命中邻接节点数。
9. **写入管理为后续扩展**：B 端在线增删 RAG 文本 / 图谱节点属后续扩展功能，需先修订 ADR-0006（仅人工 seed）/ADR-0010（运行时只读）约束，当前严格遵守只读边界。建议拆成两个新票（RAG 管理、Graph 管理），暂不实施。
