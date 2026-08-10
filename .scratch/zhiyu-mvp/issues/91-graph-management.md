# 91 — 医学图谱在线管理（管理员增改）

**What to build:** B 端新增"图谱管理"页，管理员可在线编辑 Neo4j 医学图谱：修改节点属性、增删节点、增删关系。写入链路为 server-java 直连 Neo4j（扩展现有只读 Driver seam 为可写），不经过 server-py；读链路（G6 可视化页）保持不变。编辑范围限定 Symptom/Disease/Department 三类节点及 INDICATES/TREATED_BY/SUGGESTS_DEPARTMENT 三类关系；Medication/Contraindication 节点与药品相关关系继续走 PG + seed 离线链路。同步修订 ADR-0006（运行时只读 → 读经 server-py、写经 server-java seam）。

**Blocked by:** 无（票 13 已 done）

**Status:** claimed

- [x] server-java 新增图谱写 service + controller（`/api/b/knowledge/graph` 写端点），复用 `Neo4jDriverConfig` seam 扩为可写；label 与关系类型白名单校验，越白名单拒绝
- [x] node_id 由服务端按 `{label}:{natural_key}` 生成，客户端不可指定；name 必填，重名经 Neo4j 唯一约束 + service 预检返回友好错误
- [x] 删除保护：拒绝删除仍带关系的节点（409 + 关系计数），不开放 DETACH DELETE；关系删除为显式独立操作
- [x] pgvector 对齐护栏：改/删 Symptom 时查 PG `knowledge_chunks` 同名记录，命中则在响应中带警告字段，B 端弹提示"该症状关联 RAG 知识块，建议同步维护"；不做 Neo4j+PG 联动双写
- [ ] B 端"图谱管理"列表页（节点/关系两个 tab，表格 + 表单 Modal），路由仅 admin（`ADMIN_ONLY_PATHS` + AdminInterceptor 兜底）；G6 可视化页保持只读，节点详情 Drawer 加"编辑"跳转入口；浏览器实测无控制台错误
- [x] 审计：server-java 入口统一审计，记操作人/动作/label/node_id/属性键
- [x] 修订 ADR-0006（运行时只读 → 读经 server-py、写经 server-java seam；seed.cypher 降为仅初始化，Neo4j 在线状态为准，重放 seed 与在线变更的漂移风险写明）；涉及处同步 ADR-0013
- [x] 测试：service 级单测（fake/mock Driver）覆盖增删改、删除保护、白名单拒绝、RAG 警告护栏；一条 MockMvc 冒烟；新端点权限负向测试（doctor 访问 403）
- [ ] 验收走通：登录 admin → 图谱管理页新增症状 → 加 INDICATES 边 → G6 可视化页刷新可见 → 删除边 → 删除节点

## Grilling 决策记录

1. **编辑范围 C（限定白名单）**：节点属性修改 + 增删节点 + 增删关系，但限 Symptom/Disease/Department 三类节点、INDICATES/TREATED_BY/SUGGESTS_DEPARTMENT 三类关系。Medication 节点是 PG `medications` 表的快照投影（在线改即双写不一致），Contraindication 直接驱动 server-java 用药禁忌红线规则引擎，这两类节点及 TREATS/CONTRAINDICATED_FOR/INTERACTS_WITH 关系排除在在线编辑外，继续走"改 PG + 重放 seed"离线链路。
2. **写入链路 A（server-java 直连 Neo4j 写）**：扩展 `Neo4jDriverConfig` seam 为可写，新增写 service；不经过 server-py。依据：AGENTS.md 硬约束"server-java 是唯一对外入口和业务写入方，审计统一在 server-java 入口执行"，且"server-py 知识检索直连 Neo4j"带明确"（只读）"限定。代价：读写在两个服务不对称，由 ADR-0006/0013 修订说明。否决 B（server-py 写接口，违反只读约定）。
3. **pgvector 对齐 = A + 运行时护栏**：本票只做 Neo4j 单侧写入，接受与 `knowledge_chunks` 的漂移；改/删 Symptom 时 service 查 PG 同名 knowledge_chunks，命中返回警告字段由 B 端提示。否决 B（Neo4j+PG 联动双写，违反 ADR-0006 零双写精神）与 C（阉割 name/aliases 编辑）。真正的对齐由后续 RAG 管理票解决（票 13 决策 9 已拆分）。
4. **UI 形态 = 独立管理列表页**：节点/关系两个 tab 的表格 CRUD，而非在 G6 力导图上做图编辑（拖拽建边交互复杂且脆弱）。G6 可视化页保持只读，仅加"编辑"跳转入口。
5. **删除保护**：服务端拒绝删除仍带关系的节点（409 + 关系计数），前端提示先删关系；不开放 DETACH DELETE，避免误操作连带删边。
6. **ID 与校验**：node_id 服务端生成（`{label}:{natural_key}`），客户端不可指定；label/关系类型白名单服务端强校验；重名靠 Neo4j 唯一约束兜底 + service 预检友好报错。
7. **seed.cypher 漂移**：在线状态为准，seed.cypher 降为仅初始化。因 name 是自然键，改名后重放 seed 会按旧名 MERGE 出新节点——此风险写入 ADR-0006 修订，本票不做 seed 自动回写/导出。
8. **权限**：无新增决策——`/api/b/**` catch-all 已 admin-only 兜底，前端 `ADMIN_ONLY_PATHS` 加新路由即可；本票改了权限边界（新端点），按 AGENTS 约定补 doctor 访问 403 负向测试。

## Comments

- 2026-08-10：grilling 会话产出，用户确认全部按推荐答案定案（决策 1-3 为逐题确认，4-8 为一次性采纳推荐）。对应票 13 决策 9 拆分出的"Graph 管理"票；RAG 管理票另行立项。
