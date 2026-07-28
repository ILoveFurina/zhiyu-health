# AGENTS.md — AI 编程项目约定

## 1. Project Overview

智愈（zhiyu-health）是医疗 B+C 平台 demo：C 端为支付宝原生小程序，B 端为 React 管理后台，后端按 ADR-0009 拆分为 Java 业务后端与 Python Agent 层。

- `server-java/`：Java / Spring Boot / MyBatis-Plus 单体服务；禁止引入 Spring Cloud、Dubbo、注册中心或网关中间件
- `server-py/`：Python 3.12+ / FastAPI / LangChain / LangGraph；依赖由根目录 `pyproject.toml` 和 `uv.lock` 管理，代码必须对照锁定版本及其官方文档
- LLM：火山方舟 OpenAI 兼容协议；对话/视觉用 `doubao-seed-2.1-turbo`，embedding 用 `doubao-embedding-vision`，语音识别/TTS 用火山引擎；调用仅发生在 server-py
- 存储：PostgreSQL 16 + pgvector、Redis、Neo4j；业务数据只存 PostgreSQL，医学知识图谱只存 Neo4j
- 前端：支付宝原生小程序 + antd-mini；B 端使用 React + TypeScript + Umi + Ant Design + AntV + Zustand

## 2. Commands

```bash
docker compose up -d
mvn -f server-java/pom.xml spring-boot:run
mvn -f server-java/pom.xml test
uv sync --frozen --dev
uv run uvicorn app.main:app --app-dir server-py --reload
uv run pytest
uv run ruff check server-py
uv run mypy server-py/app
npm --prefix admin ci
npm --prefix admin run dev
npm --prefix admin run typecheck
npm --prefix admin run build
npm --prefix miniprogram ci
```
## 3. Architecture

请求链路：端 → server-java（鉴权、审计、限流、规则引擎、业务写入）→ 对话请求经 SSE 调 server-py → LLM/工具 → token 逐跳透传回端。

- server-java 是唯一对外入口和业务写入方。controller 只做校验与装配，service 承载业务逻辑，mapper 访问 PostgreSQL/Redis；确定性规则引擎必须先于 LLM 执行
- server-py 只编排 Agent：知识检索可直连 Neo4j/pgvector（只读），业务工具必须 HTTP 回调 server-java，禁止直接写业务库
- 新代码必须进入既有分层。server-java 使用 `controller/`、`service/`、`mapper/`、`entity/`、`rule/`、`agentclient/`、`config/`；server-py 使用 `api/`、`agent/`、`tools/`、`services/`、`db/`、`core/`
- `admin/` 页面按 `pages/[Module]/index.tsx` 与页内 `components/` 组织；`miniprogram/` 页面使用 `index.{js,axml,acss,json}`，禁止 `.wxml`、`.wxss` 和 `wx.*`

## 4. Conventions

- server-java 以 MockMvc 测 HTTP 外部行为；规则引擎单测必须覆盖危险输入触发和正常输入不误触
- server-py 以 TestClient 测 Agent HTTP 接口；用 fake 替换 LLM 和业务回调，断言工具调用顺序
- 前端不写自动化测试，但必须浏览器实测无控制台错误，并人工走通“登录 → 主要页面”；仅编译或 API 冒烟不算验收
- 按 `.scratch/zhiyu-mvp/issues/` 票单施工，一票一个分支，完成后合入 `main`
- 双栈语境禁止单独使用“后端”，必须写 server-java（业务后端）或 server-py（Agent 层）
- Git 提交信息以中文为主；仅为复杂逻辑和业务规则写注释，解释设计原因及失败时的一致性保障。事务、并发、补偿、原子操作和非直观 SQL 必须就地注释
- 演示数据只能使用虚构信息。演示账号为 `admin/admin123456`、`doctor.lin/doctor123456`；SEED_* 变更时同步更新本文件

## 5. Hard Constraints

1. 所有 AI 产出必须带“仅供参考，不替代医生诊断”：server-py 生成时注入，server-java 出口兜底，界面无例外。
2. 红线症状和用药禁忌必须由 server-java 确定性规则判断，LLM 只负责表达与解释。
3. PostgreSQL 存业务实体；Neo4j 只存症状、疾病、科室、药品、禁忌等医学知识；禁止双写。server-py 对 pgvector 只读，Redis 号源计数仅由 server-java 操作。
4. 号源扣减必须使用 Redis 原子 DECR + PostgreSQL 事务对账，禁止先查后改。
5. `.env` 永不入库、不打印、不写进代码或测试。审计日志和 Agent trace 不记录患者敏感原文，只记录脱敏摘要、工具名、参数类型与结果；审计统一在 server-java 入口执行。
6. schema 由 `schema.sql` + 幂等 seed 管理，不使用迁移工具；开发期变更统一 drop + recreate + seed。
7. 单文件(除测试文件)超过约 250 行必须拆分，一个文件只承担一个职责；controller/路由处理函数禁止包含 SQL 或业务逻辑。

## 6. Gotchas

- server-py/services 仅承载知识检索/RAG；涉及业务写入的多步骤流程由 server-java service 原子化或显式编排，并向 Agent 暴露单一业务能力接口。
- Umi 运行时插件错误可能只在浏览器中出现，因此前端构建成功不代表验收通过。

## 7. Agent skills

- Issue tracker：`.scratch/zhiyu-mvp/issues/`，规则见 `docs/agents/issue-tracker.md`
- Triage labels：使用默认五类规范角色标签，规则见 `docs/agents/triage-labels.md`
- Domain docs：采用 `CONTEXT.md` + `docs/adr/` single-context 布局，规则见 `docs/agents/domain.md`
