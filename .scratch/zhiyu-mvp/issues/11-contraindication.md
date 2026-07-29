# 11 — 禁忌检测

**What to build:** Neo4j 禁忌子图（药品节点以成分作为属性 + 药品—禁忌关系边 + 药品—药品相互作用关系边）seed；禁忌事实唯一来源为 Neo4j，当前健康档案过敏史与处方/候选药品来自 PostgreSQL。server-java 在 `rule/` 中完成“过敏史 × 药品成分”和“药品 × 药品相互作用”的确定性判断；server-py 的 `check_contraindication` 只做 HTTP 回调薄壳，LLM 只负责解释判定结果。命中时必须阻断推荐并返回明确话术与界面呈现。

**Blocked by:** 09 — 电子处方（medications 表）；21 — 健康档案（当前档案过敏史）（禁忌数据在 Neo4j，与 RAG 无关）

**Status:** ready-for-agent

- [ ] Neo4j 禁忌子图 seed：药品—禁忌关系边及药品—药品相互作用关系边，并通过 `medication_id` 与 PostgreSQL medications 对齐；两端只共享稳定 ID，不双写药品业务字段
- [ ] server-java 新增只读 Neo4j 禁忌事实访问 seam 与 `rule/` 确定性规则引擎；Neo4j 客户端不得进入 mapper 或 controller，规则结果、决定值和卡片消息类型从 `contracts/` 加载
- [ ] server-java 暴露单一禁忌检查能力接口；从已鉴权患者的当前健康档案读取过敏史，不接受 LLM 提供患者身份或过敏史原文
- [ ] server-py 的 `check_contraindication` 工具仅回调 server-java；涉及药品推荐时必须先检查，命中后不得继续推荐或展示未经复检的替代药
- [ ] 拦截话术（说明原因 + 建议咨询医生）与卡片呈现；LLM 解释必须带免责声明，规则判定本身不由 LLM 改写
- [ ] server-java 规则单测覆盖危险输入必触发、正常输入不误触、药品相互作用与数据缺失时安全降级；MockMvc 覆盖越权/无当前档案/未知 medication_id 等负向分支
- [ ] server-py 用 fake 业务回调断言工具调用顺序，且不直连或写入业务库

## Comments

- 2026-07-29：按硬约束把禁忌“判断方”从 Agent 工具改为 server-java 确定性规则引擎。Neo4j 仍是禁忌知识唯一来源，server-py 不得根据图查询结果自行作安全决定。
