# 知识源现场切换：Redis 单键 + 逐请求补位

Status: accepted（票 25 演示武器包）

知识源选择器（`contracts/knowledge.json` 三态 `rag`/`graph`/`none`）原本只在 C 端对话请求里逐请求透传、从不持久化（ADR-0010）。票 25 要"现场切换出口"供 demo 评审对比三态效果，因此首次引入一个运行时可变的选中态。

决策：server-java 在 Redis 存全局单键 `demo:knowledge_source`（值域 `rag`/`graph`/`none`，默认 `none`）。B 端 `PUT /api/b/demo/knowledge-source`（admin 鉴权）写该键；每条 C 端对话请求到达 server-java 时，若请求**未显式携带** `knowledge_source`，则读该键补位透传给 server-py，优先级为"请求 > 全局键 > scenario 默认"。server-py 完全不感知开关存在，收到的仍是逐请求的 `knowledge_source` 字段，工具注入逻辑一字未改。现场切换为串行：B 端切一次，C 端发新对话看效果，不做并行三路对比。

## 被否决的方案

- **server-java 进程内内存变量**：实现更简，但 server-java `--reload` 重生会丢状态（项目已知坑，见 `docs/engineering-notes/wss-and-windows-service-pitfalls.md`），且多实例不一致；Redis 已是号源计数的既有依赖，单键无额外基础设施成本。
- **PostgreSQL 配置表**：持久且可审计，但为 demo 现场开关引入一张业务表，与"PostgreSQL 只存业务实体"的语义边界冲突；开关本身不需要持久化（重启回到默认 `none` 是可接受的）。
- **对比开关走 demo 专用对话入口**（完全不碰 `ChatController`）：边界最纯，但 C 端小程序演示时无法看到效果，失去"现场"意义。当前选择只在"请求未带值时补位"，默认 `none` 等价于开关关闭、与现状完全一致，是纯补位、默认无副作用。

## Consequences

- 这是演示武器包里**唯一**与正常业务路径相交的点：`ChatController`/`ChatWebSocketHandler` 在透传前多一步"请求未带值则读 Redis 键"。该交点是只读补位，不改 server-py 任何逻辑。
- 优先级"请求 > 全局 > 默认"保证现有带 `knowledge_source` 的调用方（含 server-java 内部发起的带值请求与所有契约测试）行为不变；开关只对"不带值"的请求生效。
- 开关状态非持久：Redis 重启或键过期后回到默认 `none`。demo 评审前需确认键存在，属可接受的人工步骤。
