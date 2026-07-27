# 11 — 禁忌检测

**What to build:** Neo4j 禁忌子图（药品节点以成分作为属性 + 药品—禁忌关系边 + 药品—药品相互作用关系边）seed；`check_contraindication` 工具接入 Agent：涉及药品推荐时，以当前健康档案过敏史 × 禁忌规则做硬规则拦截，拦截时给出明确话术与界面呈现。规则引擎唯一数据源为 Neo4j（PG 的 medications 不含禁忌/相互作用数据，遵守不双写）；过敏史来源为 seed 数据与票 21 的档案表单。

**Blocked by:** 09 — 电子处方（medications 表）；21 — 健康档案（当前档案过敏史）（禁忌数据在 Neo4j，与 RAG 无关）

**Status:** ready-for-agent

- [ ] Neo4j 禁忌子图 seed：药品—禁忌关系边及药品—药品相互作用关系边，并通过 `medication_id` 与 medications 对齐
- [ ] check_contraindication 工具：推荐药品前必查
- [ ] 拦截话术（说明原因 + 建议咨询医生）与卡片呈现
- [ ] 过敏 × 药品、药品 × 药品相互作用规则的 service 层单元测试
