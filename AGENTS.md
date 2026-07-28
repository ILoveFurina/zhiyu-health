# AGENTS.md — AI 编程项目宪法

本文件约束所有 AI 编程工具在本仓库的行为。开工前必读，与 `CONTEXT.md`、`docs/adr/` 配套。

## 项目

智愈（zhiyu-health）：医疗 B+C 平台 demo。C 端支付宝小程序（医疗 AI Agent）、B 端 React 管理后台、Java + Python 双栈后端（ADR-0009）。周期两周，评审交付。

## 技术栈（锁死，不得擅自替换）

- 业务后端 `server-java/`：Java / Spring Boot / MyBatis-Plus。单体服务，不引入 Spring Cloud、Dubbo、注册中心、网关中间件
- Agent 层 `server-py/`：Python 3.12+ / FastAPI（仅作 Agent 的 HTTP 壳）/ LangChain + LangGraph / uv 管理依赖（`pyproject.toml` 声明依赖，`uv.lock` 锁定精确解析版本并入库）。**生成相关代码必须对照 `uv.lock` 中的实际版本及对应官方文档，禁止凭记忆混用新旧 API**
- LLM：火山方舟（OpenAI 兼容协议）。对话/视觉 `doubao-seed-2.1-turbo`，embedding `doubao-embedding-vision`。C 端对话推理档默认自动（场景分配：导诊低、解读高）。语音识别/TTS 用火山引擎语音服务（小程序录音 → 识别 REST → 文本进对话），与火山方舟同账号体系。LLM/语音调用只发生在 server-py
- 存储：PostgreSQL 16 + pgvector（业务 + 向量）/ Redis（号源计数、缓存）/ Neo4j（仅医学知识图谱）。访问权：业务表写入 = 仅 server-java；向量检索 = server-py 只读；Neo4j = 仅 server-py 直连；Redis 号源计数 = 仅 server-java
- 前端：支付宝原生小程序 + antd-mini；React + TypeScript + Umi + Ant Design + AntV + Zustand（B 端禁用 Vue，赛题硬约束）
- schema 管理：`schema.sql` + 幂等 seed（server-java 启动时执行），不使用迁移工具；开发期 schema 演进统一执行 drop + recreate + seed，不在旧表上做隐式迁移

## 分层纪律（双栈版）

请求链路：端 → server-java（鉴权/审计/限流 → 规则引擎 → 业务写入）→ 对话类请求经 SSE 流式调 server-py → LLM/工具 → token 逐跳透传回端。

- server-java：controller 只做参数校验与装配 → service 承载业务逻辑 → mapper/MyBatis-Plus → PG/Redis。规则引擎（红线症状、用药禁忌）是独立确定性组件，先于一切 LLM 调用执行。
- server-py：tool 函数是薄壳——知识检索类直连 Neo4j/pgvector（只读）；业务类一律 HTTP 回调 server-java，不直接碰业务库。LangGraph 只编排 LLM 与工具的循环。
- server-py 无业务写入权；server-java 不感知 prompt/图结构。两端各自可独立推倒迭代。

## 目录结构与拆分红线

各端代码必须落进既有骨架，新增代码先找该去的层，找不到归属就先提议再建文件，禁止平铺：

```
server-java/src/main/java/.../
├── controller/    # HTTP 层（c/ 与 b/ 子包分端）
├── service/       # 业务逻辑，一领域一文件
├── mapper/        # MyBatis-Plus mapper，一聚合一文件
├── entity/        # 实体，一聚合一文件
├── rule/          # 确定性规则引擎（红线症状、用药禁忌）
├── agentclient/   # 调 server-py 的 SSE 客户端
└── config/        # 安全、schema/seed 初始化
```

```
server-py/app/
├── main.py          # 装配：Agent 接口挂载
├── api/             # Agent HTTP 接口（供 server-java 调用）
├── agent/           # LangGraph 图、状态、prompts
├── tools/           # 工具薄壳，一领域一文件（知识检索直连 + 业务 HTTP 回调）
├── services/        # 仅知识检索/RAG 类逻辑
├── db/              # Neo4j 客户端、pgvector 只读检索
└── core/            # 日志、安全、异常
```

`admin/`（Umi + React，对齐赛题结构模板）：`config/`（config.ts、routes.ts）、`src/`（access.ts、app.tsx、models/、services/、pages/、components/、utils/），页面按 `pages/[Module]/index.tsx` + 页内 `components/` 组织。

`miniprogram/`（支付宝原生）：app.js/app.json/app.acss、mini.project.json、`pages/[module]/index.{js,axml,acss,json}`、components/、services/（接口封装）、utils/。禁止出现 .wxml/.wxss/wx.* 残留。

**红线**：单文件超过约 250 行必须拆分；一个文件一个职责；controller/路由处理函数里不得出现 SQL/业务逻辑，只能调 service。

## 硬规则（违反即返工）

1. **免责声明标注**：一切 AI 产出（导诊建议、病情摘要、处方/报告解读、拍照分析、就诊小结、用药解释与替代建议等）必须带"仅供参考，不替代医生诊断"。server-py 生成时注入，server-java 出口兜底校验，界面无例外。
2. **安全判断走规则不走 LLM**：红线症状、用药禁忌由 server-java 的确定性规则引擎判断，LLM 只做表达与解释。
3. **图谱边界**：Neo4j 只存医学知识节点（症状/疾病/科室/药品/禁忌），业务实体一律在 PostgreSQL，任何数据不双写。
4. **号源防超卖**：扣减 = Redis 原子 DECR + PG 事务对账，禁止先查后改。仅 server-java 执行。
5. **密钥**：`.env` 永不入库、不打印、不写进代码或测试。
6. **演示数据与日志**：仅使用虚构演示数据；审计日志与 Agent trace 不落患者敏感原文，只记录脱敏摘要、工具名、参数类型与执行结果。审计统一在 server-java 入口落。
7. **写注释**: 仅在复杂逻辑、业务规则或特殊处理处添加注释。注释精简，应解释“为什么这么做”，而不是“代码做了什么”。
## 测试

- server-java 主 seam：HTTP API 层（MockMvc），只测外部行为；规则引擎单测覆盖危险输入必触发、正常输入不误触
- server-py 主 seam：Agent HTTP 接口层（TestClient）；LLM 用 fake 替换，断言工具调用序列与对 server-java 的业务回调（回调用 fake HTTP 替身）
- 前端两端不写自动化测试，但前端票验收必须包含浏览器实测：打开页面无控制台报错、核心链路（登录 → 主要页面）人工点一遍。仅编译通过或 API 冒烟不算完成——umi 运行时插件注册等错误只在浏览器端暴露
- 演示账号凭据随 seed 落档于本文件，SEED_* 变更必须同步更新，不留"只有历史会话知道"的密码。当前为 admin/admin123456、doctor.lin/doctor123456（虚构演示凭据）
## 工作流

按 `.scratch/zhiyu-mvp/issues/` 票单施工（阻塞关系见各票 "Blocked by"），一票一个分支，完成合 `main`。

"后端"一词在双栈语境下有歧义，沟通、票单与代码注释中必须指明 server-java（业务后端）或 server-py（Agent 层）。

Git 提交信息以中文为主，必要的技术名词、命令和约定式提交前缀可保留英文。
