# C 端药品说明收口为通用知识流，个性化禁忌仅留 B 端开方

Status: accepted（票 51 拍药盒收口与说明书流；票 53 将固定四节修订为半结构化 Markdown）；部分取代 ADR-0025

票 14（ADR-0025）为 C 端拍药盒建了「vision OCR 提名 → 查 medications 表 → 规则引擎禁忌判定 → 说明书卡片 + 安全卡片双出口」链路。题目 59（C 端只解读医生已开处方）与 99（禁忌检测是 B 端开方创新项）判定 C 端个性化安全出口越界：C 端 Agent 不做个性化用药决策（ADR-0016），禁忌检测的演示价值在 B 端开方流程。本 ADR 将 C 端药品说明收口为通用知识流，取代 ADR-0025 决策 1（双列查）/2（C 端规则引擎组装）/3（双出口卡片）；ADR-0025 的 vision 纯 OCR 提名与 MinIO 旁路持久化仍然有效。

## 决策

### 1. C 端拍药盒收口为「OCR 提名 → 通用说明书流」

vision 管道仍只提候选药名（ADR-0025 保留部分），之后不再查 `medications` 业务表、不读健康档案、不调规则引擎、不查 Neo4j 禁忌事实。客户端拿到药名后经 realtime 通道发送 `medication_name` 信封，server-java 透传至 server-py `/api/agent/medication/knowledge`，LLM 按通用语料流式输出说明书，经 SSE/WS 逐 token 回落会话 `KIND_TEXT` 消息。说明书不绑业务表、不引联网搜索、不做药房联动。排版为半结构化 Markdown（票 53）：开头一段简明摘要直接进入正文、不重复药品名称大标题；结尾固定为「安全提示」章节；中间由模型按药品特点自选 2～4 个最有价值章节，标题独占一行并与正文留空行，正文可用短段落、加粗或列表；不支持表格、嵌套列表、链接或代码块。

### 2. 个性化禁忌仅留 B 端开方流程

`rule/` 包、`ContraindicationService`、B 端开方实时红字提醒与提交强制复检一行不动，是禁忌能力的唯一承载。C 端删除：`MedicationLookupService` 及其对过敏原 mapper / Neo4j facts / `ContraindicationRuleEngine` 的组装、`MedicationMapper` 双列查询方法、`POST /api/c/medication-lookups`、`medication_info`/`medication_safety` 两个消息 kind 与卡片组件、「查药品」文字入口（能力经 `medication_name` 信封保留，拍药盒识别后自动携带）。

### 3. C 端处方解读原则

C 端只解读医生已批准处方的药名、用法与医生注明注意事项，不重新替医生做安全判定。说明书流是这一原则的通用知识形态：只解释药品本身的公开信息，流尾由 server-java 出口兜底注入契约免责声明并追加 `consult_professional`（「具体是否适用请咨询医生或药师」），引导语取代原 C 端禁忌判定。

### 4. 重申 ADR-0016

C 端 Agent 不做个性化用药决策，只提供通用药品知识解释并引导咨询医生或药师。收口后 C 端药品链路不再存在任何读健康档案的代码路径，ADR-0016 由架构保证而非仅靠 prompt 约束。

## Consequences

- `contracts/medication-knowledge.json` 新增（token/done 流事件、`consult_professional`、`unknown_drug` 话术）；`chat-realtime.json` 的 `chat` 信封增加可选 `medication_name`；`sse-events.json` 移除 `medication_info`、`medication_safety` kind。双端钉死测试同步。
- server-py 新增 `/api/agent/medication/knowledge`：prompt 半结构化 Markdown 排版边界（摘要 + 2～4 个自由章节 + 安全提示收尾），不接收任何患者档案字段，禁止个性化剂量与替代药建议，不认识的药输出 `unknown_drug` 话术。
- server-java WS/SSE 双通道识别 `medication_name`（与 `content` 互斥）：入口审计只记脱敏药名，token 逐跳透传，轮次落 `KIND_TEXT`，流尾双话术兜底。
- C 端拍药盒隐私面收窄：上传链路不再读档案，无档案用户体验与有档案一致。
- B 端禁忌链路（票 11 底座）为个性化用药安全的唯一演示点，位于开方 seam 之后。
