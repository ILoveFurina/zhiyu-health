# 13 — 医学知识图谱全量与可视化

**What to build:** Neo4j 图谱五类节点（症状/疾病/科室/药品/禁忌）数据全量 seed；server-py 运行时只读 Neo4j，`traverse_graph` 工具接入 Agent（检索时沿边一跳扩展，GraphRAG 雏形）；B 端“医学知识图谱”页通过 server-java 对外入口读取图谱投影，以 AntV 力导向图渲染并支持点击节点看详情。

**Blocked by:** 29 — 票 02 业务迁移（Java）；10 — RAG 知识库

**Status:** ready-for-agent

- [ ] 图谱 seed 与知识库场景同源，至少 150 个节点、200 条关系边；seed 属离线初始化，运行时 server-py 不写 Neo4j
- [ ] server-py 只读知识 service + `traverse_graph` 工具接入 Agent；TestClient 用 fake 图客户端覆盖一跳扩展与关闭增强时不查询
- [ ] server-java 提供已鉴权的 B 端只读图谱查询入口并转调 server-py 内部知识接口；不得让 admin 浏览器直连 server-py 或 Neo4j
- [ ] B 端图谱可视化页使用项目约定的 AntV graph，节点点击展示详情；浏览器实测无控制台错误
- [ ] 架构守卫：图谱只装知识，业务实体零双写（ADR-0006）
- [ ] 图谱增强决定值从 `contracts/` 推导，并与票 10 的知识增强契约联动；现场切换 UI 留给票 25

## Comments

- 2026-07-29：按当前技术栈将旧 ECharts 描述改为 AntV，并明确 B 端只能经 server-java 访问、server-py 对 Neo4j 运行时只读。
