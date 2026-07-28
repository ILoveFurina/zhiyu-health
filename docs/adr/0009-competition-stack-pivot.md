# 响应赛题约束的技术栈调整：B 端 React 化与后端双栈拆分

Status: accepted（部分取代 ADR-0001）

阶段三赛题新增项目结构与技术栈约束（`docs/competition-contraint/`）：B 端强制 React + TypeScript（禁用 Vue），并给出 Umi/Bigfish 形态的项目结构模板；C 端支付宝小程序或 H5 二选一（禁用微信——C 端转向已由 ADR-0008 先行定案并落地支付宝）；后端强制 Java（赛题方阶段三约束）。ADR-0001"全 Python 单体"的立论（避免跨语言集成成本、单一语言生成优先）与赛题硬约束冲突，必须调整。

## 决策 A：B 端 Vue → React（Umi）

`admin/` 推倒重建：Umi + React + TypeScript + Ant Design + AntV + Zustand，目录结构对齐赛题模板（`config/`、`src/access.ts`、`src/app.tsx`、`src/models/`、`src/services/`、`src/pages/`）。选 Umi 而非 Vite + React Router：赛题结构模板即 Umi/Bigfish 约定（config.ts、routes.ts、access.ts、models/），贴合成本最低；赛题请求库清单亦以 umi request 为先。

## 决策 B：后端单体拆双栈

保留 Python Agent 层，业务承载移交 Java，两服务平铺：

- `server-java/`：Spring Boot + MyBatis-Plus 单体。唯一对外入口与唯一业务写入方：鉴权/会话/审计/限流、B 端全部 API 与 C 端业务 API、规则引擎（红线症状、用药禁忌——硬规则 2 的归属端）、号源防超卖（Redis 原子 DECR + PG 事务对账，硬规则 4）。schema 管理为 `schema.sql` + 幂等 seed（启动执行），开发期 drop + recreate + seed，不做隐式迁移——沿用原约定的精神，替换工具形态。
- `server-py/`：现有 `server/` 更名瘦身。保留 LangChain + LangGraph（ADR-0005）、火山方舟 LLM/语音链路（ADR-0004）与知识检索（Neo4j、pgvector 直连只读）；FastAPI 从业务单体退化为 Agent 编排的 HTTP 壳；业务工具改为薄壳 HTTP 回调 server-java，无业务写入权。
- 对话链路：统一 server-java 入口——鉴权/审计后 SSE 流式调 server-py，token 逐跳透传回小程序。审计日志（脱敏摘要，硬规则 6）只在入口落一处。
- 明确不引入 Spring Cloud、Dubbo、注册中心、网关中间件：两周 demo 体量，双服务直连。

数据访问权边界：业务表写入 = 仅 server-java；向量检索 = server-py 只读；Neo4j = 仅 server-py 直连（禁忌类规则的判断在 server-java，其所需的图谱禁忌知识经 server-py 只读知识接口获取，规则判断不出 Java）；Redis 号源计数 = 仅 server-java。

存量处置：票 02/03/04 已验收的 Python 业务代码按新边界迁移——新增票 28（server-java 骨架 + server-py 瘦身 + 对话链路骨架）、29（票 02 迁移）、30（票 03 迁移）、31（票 04 拆分：会话持久化/对话入口/红线规则迁 server-java，LangGraph 循环留 server-py）；票 05 起未开工票按双栈直接施工，Blocked by 改指 29/30/31。票 05–27 编号不重排，保留历史。

受影响的既有决策：ADR-0001 业务承载部分被取代（Agent 承载仍在 Python/FastAPI）；ADR-0003 存储选型不变，访问权按上表划分；ADR-0004/0005 不变；ADR-0008 不变。

考虑过的替代方案：全量 Java（用 LangChain4j/Spring AI Alibaba 重写 Agent 层——核心 AI 编排返工风险压在两周交付上，拒绝）；维持 Python 单体（违反赛题硬约束，拒绝）；对话请求直连 server-py（破坏唯一入口、鉴权审计分散，仅留作 SSE 透传故障时的降级预案）；B 端选 Vite + React Router（赛题结构模板为 Umi 约定，拒绝）；C 端选 H5（ADR-0008 已定支付宝，拒绝）。
