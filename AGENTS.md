# AGENTS.md — AI 编程项目宪法

本文件约束所有 AI 编程工具在本仓库的行为。开工前必读，与 `CONTEXT.md`、`docs/adr/` 配套。

## 项目

智愈（zhiyu-health）：医疗 B+C 平台 demo。C 端支付宝小程序（医疗 AI Agent）、B 端 Vue3 管理后台、FastAPI 单体后端。周期两周，评审交付。

## 技术栈（锁死，不得擅自替换）

- 后端：Python 3.12+ / FastAPI / SQLAlchemy / uv 管理依赖（`pyproject.toml` 声明依赖，`uv.lock` 锁定精确解析版本并入库）
- Agent：LangChain + LangGraph。**生成相关代码必须对照 `uv.lock` 中的实际版本及对应官方文档，禁止凭记忆混用新旧 API**
- LLM：火山方舟（OpenAI 兼容协议）。对话/视觉 `doubao-seed-2.1-turbo`，embedding `doubao-embedding-vision`。C 端对话推理档默认自动（场景分配：导诊低、解读高）。语音识别/TTS 用火山引擎语音服务（小程序录音 → 识别 REST → 文本进对话），与火山方舟同账号体系
- 存储：PostgreSQL 16 + pgvector（业务 + 向量）/ Redis（号源计数、缓存）/ Neo4j（仅医学知识图谱）
- 前端：支付宝原生小程序 + antd-mini；Vue3 + Element Plus + Vite
- schema 管理：`create_all` + 幂等 seed，不使用 Alembic；开发期 schema 演进统一执行 drop + recreate + seed，不在旧表上做隐式迁移

## 分层纪律

tool 函数是薄壳（仅参数校验）→ service 层承载业务逻辑 → 数据库/Redis/Neo4j。框架只编排 LLM 与工具的循环。

## 目录结构与拆分红线

`server/` 内代码必须落进既有骨架，新增代码先找该去的层，找不到归属就先提议再建文件，禁止平铺：

```
server/app/
├── main.py          # 装配：路由挂载、中间件
├── config.py        # pydantic-settings
├── api/             # HTTP 层（c/ 与 b/ 子目录分端）
├── services/        # 业务逻辑，一领域一文件
├── tools/           # Agent 工具薄壳，一领域一文件
├── agent/           # LangGraph 图、状态、prompts
├── models/          # SQLAlchemy 模型，一聚合一文件
├── schemas/         # Pydantic 请求/响应模型
├── db/              # 连接、session、seed
└── core/            # 日志、安全、异常
```

**红线**：单文件超过约 250 行必须拆分；一个文件一个职责；路由处理函数里不得出现 SQL/业务逻辑，只能调 service。

## 硬规则（违反即返工）

1. **免责声明标注**：一切 AI 产出（导诊建议、病情摘要、处方/报告解读、拍照分析、就诊小结、用药解释与替代建议等）必须带"仅供参考，不替代医生诊断"，无例外界面。
2. **安全判断走规则不走 LLM**：红线症状、用药禁忌由确定性规则引擎判断，LLM 只做表达与解释。
3. **图谱边界**：Neo4j 只存医学知识节点（症状/疾病/科室/药品/禁忌），业务实体一律在 PostgreSQL，任何数据不双写。
4. **号源防超卖**：扣减 = Redis 原子 DECR + PG 事务对账，禁止先查后改。
5. **密钥**：`.env` 永不入库、不打印、不写进代码或测试。
6. **演示数据与日志**：仅使用虚构演示数据；日志与 Agent trace 不落患者敏感原文，只记录脱敏摘要、工具名、参数类型与执行结果。

## 测试

- 主 seam：FastAPI HTTP API 层（TestClient），只测外部行为
- LLM 在测试中用 fake 替换，断言工具调用序列与业务副作用
- 前端两端不写自动化测试

## 工作流

按 `.scratch/zhiyu-mvp/issues/` 票单施工（阻塞关系见各票 "Blocked by"），一票一个分支，完成合 `main`。

Git 提交信息以中文为主，必要的技术名词、命令和约定式提交前缀可保留英文。
