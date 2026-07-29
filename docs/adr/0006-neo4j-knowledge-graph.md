# 知识图谱：真图数据库 Neo4j，但严格限定边界

Status: accepted

团队要求使用真正的图数据库而非关系表模拟，决定引入 Neo4j 5（Docker Compose 第三个组件）承载医学知识图谱。数据为手工策划的数百条三元组（LLM 辅助生成 + 人工校对），LangChain 侧使用官方 `langchain-neo4j` 集成。图谱可视化由 B 端使用 AntV 力导向图渲染；浏览器仅访问 server-java 的已鉴权接口，由 server-java 转调 server-py 的只读知识接口获取 Neo4j 投影（ADR-0009、票 13）。

**边界（本决策的核心）**：

- Neo4j 只存医学知识节点：症状、疾病、科室、药品、禁忌 + 关系边；其中 PG `medications` 是药品业务信息唯一权威来源，Neo4j 药品节点只保留 `medication_id` 与名称快照作为图谱关联键，禁忌/相互作用等医学关系只存在 Neo4j；
- 业务实体（医生、排班、号源、挂号单）全部留在 PostgreSQL，"科室→医生"的最后一跳由 Agent 工具查 PG 完成；
- 业务字段与医学关系不双写，图谱中不建医生影子节点；药品名称快照不作为业务读取源，seed 时按 `medication_id` 对齐——避免双写一致性事故。

被否决的方案：PostgreSQL 两张表（kg_nodes/kg_edges）模拟图（团队要求真图数据库）；Neo4j 原生向量索引（向量检索维持 pgvector，形成"pgvector 向量召回 + Neo4j 一跳扩展"的双层 GraphRAG 叙事）。

成本记录：引入第三个数据组件与 Cypher 调试面，估 +1 天，优先级 P2、可插拔——主闭环不依赖图谱，砍了不影响演示主流程。
