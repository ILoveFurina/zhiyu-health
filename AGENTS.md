# AGENTS.md — AI 编程项目约定

## 1. Project Overview

智愈（zhiyu-health）是医疗 B+C 平台 demo：C 端为支付宝原生小程序，B 端为 React 管理后台，后端按 ADR-0009 拆分为 Java 业务后端与 Python Agent 层。

- `server-java/`：Java / Spring Boot / MyBatis-Plus 单体服务；禁止引入 Spring Cloud、Dubbo、注册中心或网关中间件
- `server-py/`：Python 3.12+ / FastAPI / LangChain / LangGraph；依赖由根目录 `pyproject.toml` 和 `uv.lock` 管理，代码必须对照锁定版本及其官方文档
- LLM：火山方舟 OpenAI 兼容协议；对话/视觉用 `doubao-seed-2.1-turbo`，embedding 用 `doubao-embedding-vision`，语音识别/TTS 用火山引擎；调用仅发生在 server-py
- 存储：PostgreSQL 16 + pgvector、Redis、Neo4j、MinIO；业务数据只存 PostgreSQL，医学知识图谱只存 Neo4j，图片对象（拍照分析原图、医生头像、在线问诊交流图片）存 MinIO（ADR-0023/0029）
- 前端：支付宝原生小程序 + antd-mini；B 端使用 React + TypeScript + Umi + Ant Design + AntV + Zustand

### 运行拓扑（硬约束）

- 云服务器只提供已部署的 PostgreSQL、Redis、Neo4j、MinIO 数据服务；server-java、server-py、admin、小程序开发者工具和全部测试均在本地运行
- 未经用户明确要求，禁止通过 SSH 登录云服务器、上传代码、部署应用、执行远程命令、重启服务或改动云端 Compose；数据库连接失败时只检查本地配置和安全组白名单并报告
- 云演示库 zhiyu 的 schema 演进由 AI 自动执行，不再留作人工事项：凡改动 `schema.sql` 的票完成后，必须运行 `uv run python scripts/reset_zhiyu.py`（drop + recreate + seed，各阶段单事务、失败可整体重跑，脚本硬断言目标库名为 zhiyu，绝不触碰 zhiyu_it / zhiyu_test），并用 `uv run python scripts/verify_zhiyu.py` 只读验证形状与 seed 基线。演示数据全为虚构 seed，重建无副作用；该操作经 `.env` 本地直连，不属于 SSH/部署/远程命令。`seed-knowledge.sql` 向量回填是独立离线步骤（optional，不入库），不在重建范围内
- 日常开发不得执行 `docker compose up`；`compose.yaml` 仅供人工进行云数据库首次部署或维护
- 本地进程通过 `.env` 中的云数据库地址直连；不得打印 `.env` 内容或连接凭据

## 2. Commands

一键启动本地三服务（server-py / server-java / admin，各开独立窗口并等待健康检查）：
`powershell -File scripts/dev-up.ps1`；小程序仍需支付宝开发者工具手动导入。

```bash
mvn -f server-java/pom.xml spring-boot:run
mvn -f server-java/pom.xml test
mvn -f server-java/pom.xml spotless:check
uv sync --frozen --dev
uv run uvicorn app.main:app --app-dir server-py --reload
uv run pytest
uv run ruff check server-py
uv run mypy server-py/app
uv run lint-imports
npm --prefix admin ci
npm --prefix admin run dev
npm --prefix admin run typecheck
npm --prefix admin run build
npm --prefix miniprogram ci
uv run python scripts/reset_zhiyu.py
uv run python scripts/verify_zhiyu.py
```
## 3. Architecture

请求链路：端 → server-java（鉴权、审计、限流、规则引擎、业务写入）→ 对话请求经 SSE 调 server-py → LLM/工具 → token 逐跳透传回端。

- server-java 是唯一对外入口和业务写入方。controller 只做校验与装配，service 承载业务逻辑，mapper 访问 PostgreSQL/Redis；确定性规则引擎必须先于 LLM 执行
- server-py 只编排 Agent：知识检索可直连 Neo4j/pgvector（只读），业务工具必须 HTTP 回调 server-java，禁止直接写业务库
- 新代码必须进入既有分层。server-java 使用 `controller/`、`service/`、`mapper/`、`entity/`、`rule/`、`agentclient/`、`config/`；server-py 使用 `api/`、`agent/`、`tools/`、`services/`、`db/`、`core/`
- `admin/` 页面按 `pages/[Module]/index.tsx` 与页内 `components/` 组织；`miniprogram/` 页面使用 `index.{js,axml,acss,json}`，禁止 `.wxml`、`.wxss` 和 `wx.*`

## 4. Conventions

- 新票测试从简、分层施工；存量测试不做收敛或删除：
  - 必须：规则引擎/红线逻辑单测覆盖危险输入触发与正常输入不误触；跨栈契约一致性测试随 `contracts/` 增量同步维护（`ContractsTest`）
  - 常规：server-java 新功能以 service 级单测为主，每模块一条 MockMvc 主链路冒烟；server-py 以 TestClient 测 Agent 接口，LLM 与业务回调用 fake，断言工具调用顺序
  - 按需（仅当票真正改动权限边界、约束或并发逻辑）：负向 HTTP 测试、PostgreSQL 集成测试（`-Dpg.it=true` 门控）
  - 回归只跑受影响模块 + 契约测试；票单 checklist 不再写"全量回归既有测试"
- server-java 异常只抛 `ApiException`（controller 零 try-catch，统一 advice 出口）；号源只经 `SlotAccounting`（ArchUnit 强制）；契约值只从 `contracts/` 加载；B 端新 CRUD 继承 MyBatis-Plus `ServiceImpl`；DTO 映射用 MapStruct
- 测试配置隔离：`src/test/resources/application.yml` 与 main 同步维护，不导入 `.env`
- 前端不写自动化测试，但必须浏览器实测无控制台错误，并人工走通“登录 → 主要页面”；仅编译或 API 冒烟不算验收
- 按 `.scratch/zhiyu-mvp/issues/` 票单施工，一票一个分支，完成后合入 `main`
- 双栈语境禁止单独使用“后端”，必须写 server-java（业务后端）或 server-py（Agent 层）
- Git 提交必须遵循 Conventional Commits，格式为 `type(scope): 中文摘要`（scope 可省略）；仅为复杂逻辑和业务规则写注释，解释设计原因及失败时的一致性保障。事务、并发、补偿、原子操作和非直观 SQL 必须就地注释
- 演示数据只能使用虚构信息。演示账号为 `admin/admin123456`、`doctor.lin/doctor123456`、`doctor.zhou/doctor123456`；其余 13 位医生（doctors id 3-15）用户名 `doctor.<姓拼音>`（doctor.chen ~ doctor.han），统一密码 `doctor123456`（`SEED_DOCTORS_PASSWORD`，缺省同值）；SEED_* 变更时同步更新本文件
- 新 CRUD/跨栈契约票提交前必须确认：B 端 service 继承 `ServiceImpl`；DTO/Entity/View 映射全部使用 MapStruct；状态、决定、消息类型及其 TS 类型均从 `contracts/` 推导；票单状态与 checklist 已更新。审查疑点先核对现有拦截器和后续票边界，必要时补负向 HTTP 测试，不越票实现。
- 任意票单置 `done` 前必须确认：`README.md` 依赖关系图中对应节点已在数字前加 `[x]`（形如 `T29["[x]29 组织管理迁移"]`，双引号包裹、`[x]` 紧贴数字）；未完成节点保持数字开头不加标记。撤回 `done` 时须同步移除该 `[x]`。

## 5. Hard Constraints

1. 所有 AI 产出必须带“仅供参考，不替代医生诊断”：server-py 生成时注入，server-java 出口兜底，界面无例外。
2. 红线症状由 server-java 在 C 端对话入口确定性判断；用药禁忌仅在 B 端医生开方流程由 server-java 确定性判断。C 端 Agent 不做个性化用药决策，只提供通用药品知识解释并引导咨询医生或药师。
3. PostgreSQL 存业务实体；Neo4j 只存症状、疾病、科室、药品、禁忌等医学知识；MinIO 旁路存储图片对象：拍照分析原图、医生头像与在线问诊交流图片（ADR-0023、ADR-0029）；禁止双写。server-py 对 pgvector 只读，Redis 号源计数仅由 server-java 操作。
4. 号源扣减必须使用 Redis 原子 DECR + PostgreSQL 事务对账，禁止先查后改。
5. `.env` 永不入库、不打印、不写进代码或测试。审计日志和 Agent trace 不记录患者敏感原文，只记录脱敏摘要、工具名、参数类型与结果；审计统一在 server-java 入口执行。
6. schema 由 `schema.sql` + 幂等 seed 管理，不使用迁移工具；开发期变更统一 drop + recreate + seed，由 AI 按“运行拓扑”一节在 schema 变更票完成后自动重建云演示库并验证。
7. 一个文件只承担一个职责；controller/路由处理函数禁止包含 SQL 或业务逻辑。

## 6. Gotchas

- server-py/services 仅承载知识检索/RAG；涉及业务写入的多步骤流程由 server-java service 原子化或显式编排，并向 Agent 暴露单一业务能力接口。
- Umi 运行时插件错误可能只在浏览器中出现，因此前端构建成功不代表验收通过。
- 支付宝开发者工具会把 `my.connectSocket` 的自定义 header 值包一层字面双引号（`my.request` 不受影响），server-java `AuthFilter` 已兼容剥离；本地 WSS 套件（8443 覆盖配置 + 自签证书）与排障方法见 `docs/engineering-notes/wss-and-windows-service-pitfalls.md`。
- Windows 上单进程 uvicorn 默认 ProactorEventLoop，psycopg 异步拒绝运行；`--reload` 的重生在后台环境可能静默卡死。server-py 必须用 `uv run python scripts/run-server-py.py` 启动（强制 SelectorEventLoop；admin dev 端口固定 5173，避免抢占 server-py 的 8000）。

## 7. Agent skills

### Issue tracker

任务与 PRD 使用本地 Markdown 管理：PRD 位于 `.scratch/zhiyu-mvp/spec.md`，票单位于 `.scratch/zhiyu-mvp/issues/`。详见 `docs/agents/issue-tracker.md`。

普通实施票生命周期为 `ready-for-agent → claimed → done`；被替代的票标记 `retired`，`done` 与 `retired` 均视为 blocker 已解除。

### Triage labels

使用默认的五类 triage 标签。详见 `docs/agents/triage-labels.md`。

### Domain docs

采用 single-context：根目录 `CONTEXT.md` 保存领域语言，架构决策放在 `docs/adr/`。详见 `docs/agents/domain.md`。
