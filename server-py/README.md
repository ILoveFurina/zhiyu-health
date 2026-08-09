# server-py（Agent 层）代码导读

server-py 负责模型调用、Agent/工具编排、医学知识只读检索以及视觉、语音、临床文本生成。
它不是业务后端：所有外部请求先进入 server-java，由 server-java 完成鉴权、审计、限流、
红线规则和业务写入，再通过内部 HTTP/SSE 调用本进程。

所有 AI 产出必须携带“仅供参考，不替代医生诊断”。server-py 在生成出口注入，
server-java 再兜底；知识召回事实和工具 trace 不是 AI 产出，不附该文案。

## 本地运行

在仓库根目录执行：

```powershell
uv sync --frozen --dev
uv run python scripts/run-server-py.py
uv run pytest
uv run ruff check server-py
uv run mypy server-py/app
uv run lint-imports
```

Windows 必须通过 `scripts/run-server-py.py` 启动，以使用 psycopg 兼容的
SelectorEventLoop。默认 pytest 完全离线；真实知识检索集成测试需显式执行：

```powershell
$env:ZHIYU_RUN_KNOWLEDGE_INTEGRATION = "1"
uv run pytest server-py/tests/test_knowledge_integration.py -m integration
```

## 目录地图

- `app/main.py`：HTTP 入口、路由清单和生产 lifespan 接入，是阅读起点。
- `app/bootstrap.py`：真实 settings、存储客户端、server-java 回调和模型适配器装配。
- `app/runtime.py`：统一安装 FastAPI 运行期依赖，避免散落的 `app.state` 字段清单。
- `app/api/`：薄 HTTP/SSE 入口，只校验、装配请求并调用下层模块。
- `app/schemas/`：内部 HTTP 请求/响应以及结构化模型输出。
- `app/services/`：对话业务编排与知识、图谱、语音等能力。
- `app/agent/`：提示词、结构化 judge、LangGraph runner 和视觉解释管道。
- `app/tools/`：LLM 工具、只读知识工具以及回调 server-java 的 HTTP 适配器。
- `app/db/`：Neo4j 与 pgvector 客户端；运行期仅做医学知识读取。
- `app/core/`：跨栈契约加载、模型构建、日志、懒加载和事件循环等基础能力。
- `app/testing.py`：离线测试的显式 fake 装配，不读取生产 settings 或连接真实依赖。

## 普通对话调用链

```text
api.agent
  → services.chat.AgentChatService.stream
    → services.chat_guidance（确定性导诊/号源，可短路）
    → services.chat_knowledge（rag/graph/none 选择与能力降级）
    → agent.runner（LangGraph + 模型/工具循环）
      → tools.knowledge / tools.graph（医学知识只读）
      → tools.business → tools.callback → server-java（业务能力）
    → agent.events（工具 trace 脱敏、知识/卡片投影）
    → services.chat_events（项目 SSE + 免责声明）
  → meta / knowledge? / token* / message / card? / done
```

推荐按 `main.py` → `api/agent.py` → `services/chat.py` → 四个 `services/chat_*`
→ `agent/types.py` → `agent/runner.py` → `agent/events.py` → `tools/` 的顺序阅读。

## 六条核心业务链路

### 智能导诊

- 入口：`POST /api/agent/chat`，场景 `triage`。
- 核心：`services/chat_guidance.py`、`agent/triage.py`、`services/directory.py`。
- 外部依赖：server-java 标准科室目录和未来号源。
- 确定性规则：judge 只能从目录 ID 中选择；医院、医生、排班、余号和摘要模板均不由
  LLM 生成；同一已收敛科室抑制重复卡，用户明确再次挂号仍允许查询。
- 降级：目录/解析不可用时回到普通 Agent；号源查询失败输出可重试失败卡，事件顺序固定。

### 预问诊

- 入口：`POST /api/agent/chat`，场景 `preconsultation`，携带草稿 ID。
- 核心：`services/chat.py`、`agent/preconsult.py`、`services/chat_preconsultation.py`。
- 外部依赖：LLM 结构化摘要、server-java 草稿摘要回调、可选科室目录。
- 确定性规则：runner 不暴露医生推荐、号源和挂号业务工具；摘要携带免责声明；日志不记
  对话或摘要原文。
- 降级：先发 `done` 再后台生成摘要；judge、目录或回调失败均保留 server-java 上一版，
  不阻塞输入框，也不撤销已完成对话。

### 视觉解读

- 入口：`POST /api/vision/analyze`。
- 核心：`agent/vision/document.py`（输入规范化）→ `scenarios.py`（场景策略）→
  `interpreter.py`（模型解释）；这是新增视觉场景时应参考的结构。
- 外部依赖：方舟视觉模型；PDF/图片处理库。
- 确定性规则：场景限制文件类型和结构化输出；药盒只提候选药名，不做个性化用药决定；
  舌象不能生成方剂、剂量或替代诊断；原图不在 server-py 持久化。
- 降级：越界、文件非法、输出校验失败和超时映射为稳定错误码，不回显模型原始内容。

### 语音

- 入口：`POST /api/voice/asr`、`POST /api/voice/tts`。
- 核心：`api/voice.py`、`services/voice.py`。
- 外部依赖：火山 ASR/TTS。
- 确定性规则：音频只在内存流转，不进入审计或 trace；契约控制能力开关和格式。
- 降级：凭据/能力未就绪时显式选择 fake/disabled 适配器；测试始终注入 fake，不触网。

### 临床文本生成

- 入口：`POST /api/clinical/prescription-explanation`、
  `POST /api/clinical/consultation-summary`，以及通用药品说明书流。
- 核心：`agent/clinical.py`、`agent/medication.py`、对应 `api/` 路由。
- 外部依赖：方舟文本模型。
- 确定性规则：处方权限、用药禁忌和业务状态由 server-java 决定；server-py 只解释已确定
  事实。C 端只获得通用药品知识并被引导咨询医生或药师。
- 降级：模型失败映射稳定 HTTP/SSE 错误，不改变处方或问诊业务状态。

### 知识检索

- 入口：对话中的 `search_knowledge` / `traverse_graph`，以及 B 端图谱只读投影接口。
- 核心：`services/chat_knowledge.py`、`tools/knowledge.py`、`tools/graph.py`、
  `services/knowledge.py`、`services/graph.py`。
- 外部依赖：pgvector、Neo4j、方舟 embedding。
- 确定性规则：PostgreSQL 业务实体不在这里读取或写入；Neo4j 只存医学知识；rag 与 graph
  每轮互斥，召回内容是参考知识而非诊断结论。
- 降级：能力未配置会先发 `knowledge` degraded/unavailable；空召回由 runner 发 degraded，
  两者都继续裸 LLM，不把空结果伪装成医学事实。

## 医疗安全职责

server-java 决定并保证：

- C 端红线症状的确定性判断、鉴权、审计、限流和敏感信息处理；
- 医生开方时的确定性用药禁忌；
- 患者/会话/定位可信身份、业务写入、号源原子扣减和事实数据；
- 对 server-py 产出再次补齐免责声明。

server-py 可以生成：

- 非诊断性的健康知识解释、导诊表达、报告/照片有限范围解读；
- 基于 server-java 已确定事实的处方说明和问诊摘要；
- 通用药品说明，但不能面向 C 端作个性化用药选择、剂量或停换药决定。

定位代码时若一个分支涉及业务状态或医疗红线，先确认它是否应属于 server-java；不要仅因
模型能回答就把确定性规则移入提示词。
