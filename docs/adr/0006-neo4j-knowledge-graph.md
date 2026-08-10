# 知识图谱：真图数据库 Neo4j，但严格限定边界

Status: accepted

团队要求使用真正的图数据库而非关系表模拟，决定引入 Neo4j 5（Docker Compose 第三个组件）承载医学知识图谱。数据为手工策划的数百条三元组（LLM 辅助生成 + 人工校对），LangChain 侧使用官方 `langchain-neo4j` 集成。图谱可视化由 B 端使用 AntV 力导向图渲染；浏览器仅访问 server-java 的已鉴权接口，由 server-java 转调 server-py 的只读知识接口获取 Neo4j 投影（ADR-0009、票 13）。

**边界（本决策的核心）**：

- Neo4j 只存医学知识节点：症状、疾病、科室、药品、禁忌 + 关系边；其中 PG `medications` 是药品业务信息唯一权威来源，Neo4j 药品节点只保留 `medication_id` 与名称快照作为图谱关联键，禁忌/相互作用等医学关系只存在 Neo4j；
- 业务实体（医生、排班、号源、挂号单）全部留在 PostgreSQL，"科室→医生"的最后一跳由 Agent 工具查 PG 完成；
- 业务字段与医学关系不双写，图谱中不建医生影子节点；药品名称快照不作为业务读取源，seed 时按 `medication_id` 对齐——避免双写一致性事故。
- Neo4j 读取按用途分流：server-py 只读检索医学知识；server-java 仅通过 `rule/` 下的只读事实 seam 获取禁忌与药品相互作用，由确定性规则引擎作安全决定。Neo4j 驱动不得进入 controller、mapper 或其他业务 service。这样既保持禁忌事实单一来源，也避免把安全裁决交给 LLM 或 Agent 层。
- 图谱在线写入（票 91 起）只允许经 server-java 的 `service/knowledge/GraphAdminService` 写 seam：管理员在 B 端增删改 Symptom/Disease/Department 节点及 INDICATES/TREATED_BY/SUGGESTS_DEPARTMENT 关系，白名单由 `contracts/graph-management.json` 单一事实源约束。Medication/Contraindication 节点及药品相关关系仍只允许"改 PG + 重放 seed"的离线链路（药品快照防双写、禁忌事实保红线引擎权威）。server-py 对 Neo4j 保持只读。
- 在线写入开放后，Neo4j 在线状态为准，`deploy/neo4j/seed.cypher` 降为仅初始化用途。已知漂移风险：name 是自然键（node_id 为 `{label}:{natural_key}`），在线改名后重放 seed.cypher 会按旧名 MERGE 出新节点；在线变更与 seed.cypher 之间不做自动回写，维护窗口重放前需人工评估。Symptom 的 name/aliases 与 PG `knowledge_chunks.title` 的对齐不在写路径上联动（零双写），由 GraphAdminService 返回 `rag_chunk_count` 警告提示管理员同步，真正的 RAG 在线管理属后续票。

被否决的方案：PostgreSQL 两张表（kg_nodes/kg_edges）模拟图（团队要求真图数据库）；Neo4j 原生向量索引（向量检索维持 pgvector，形成"pgvector 向量召回 + Neo4j 一跳扩展"的双层 GraphRAG 叙事）。

成本记录：引入第三个数据组件与 Cypher 调试面，估 +1 天，优先级 P2、可插拔——主闭环不依赖图谱，砍了不影响演示主流程。
