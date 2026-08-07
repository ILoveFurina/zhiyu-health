# 51 — 拍药盒收口：C 端通用说明书流 + 视觉提速

**What to build:** 将 C 端拍药盒从「查药房库存表 + 个性化禁忌双卡」收口为「vision OCR 提名 → 经 realtime 通道流式输出 LLM 通用药品说明书（用途/常规用法用量/常见不良反应/常见注意事项）」。删除 C 端 `medication_safety` 卡、健康档案过敏联动、Neo4j 禁忌查询、`medications` 表双列匹配与 SAFE/BLOCKED/REVIEW_REQUIRED 出口；隐藏「查药品」入口（与拍药盒能力重复且挤占功能气泡栏），文字版能力经 `medication_name` 信封保留。说明书内容来自 LLM 通用语料，不绑业务表、不引联网搜索、不做药房联动。B 端开方禁忌链路（`rule/` 包、`ContraindicationService`、实时红字提醒、提交强制复检）一行不动。同票完成 vision OCR 提速三项。依据：题目 59（C 端只解读医生已开处方）/99（禁忌检测是 B 端开方创新项）、ADR-0016；ADR-0025 的 C 端禁忌部分为决策回摆，由本票产出的 ADR-0028 取代。

**Blocked by:** 无（14 - 拍药盒已完成；本票取代其 C 端个性化安全部分）

**Status:** claimed

## 施工顺序与 commit 约定

一票一个分支；票内按节分 Conventional commit（`type(scope): 中文摘要`），review 与回滚按 commit 粒度。顺序：§1 性能 quick win（首个 commit，立即提速）→ §2 契约 → §3 server-py → §4 server-java → §5 小程序 → §6 文档收口。契约节必须先于双栈实现节提交（跨栈契约票硬要求）；契约值只从 `contracts/` 加载，消息类型与字段双端从契约推导。

## 1. 性能 quick win（首个 commit）

- [x] `server-py/app/agent/vision/scenarios.py` 场景策略增加 reasoning_effort 配置，PILL_BOX 用 `disabled`（`build_chat_model` 已支持，经方舟 `extra_body` 透传）；REPORT/SKIN/DIET/TONGUE 保持 `high` 不变
- [x] 票单 Comments 记录改动前后本地拍药盒端到端计时对比

## 2. 契约节

- [x] `contracts/medication-knowledge.json` 新增：SSE 事件类型（token/done）、统一话术 `consult_professional`（「具体是否适用请咨询医生或药师」）、`unknown_drug` 未找到话术；免责声明复用 `contracts/disclaimer.json`
- [x] `contracts/chat-realtime.json`：`chat` 信封增加可选 `medication_name` 字段
- [x] `contracts/sse-events.json`：`message_kinds` 与 `ai_card_kinds` 移除 `medication_info`、`medication_safety`
- [x] `contraindication.json`、`vision-errors.json`、`demo-arsenal.json` 不动；`ContractsConsistencyTest` 双端钉死测试同步

## 3. server-py 节

- [x] 新增 `api/medication.py`：`POST /api/agent/medication/knowledge`，内部认证与 vision 端点同构；入参 `{drug_name}` 非空校验（422）；SSE `text/event-stream` 输出 `token` × N → `done`
- [x] prompt 边界：只做通用药品知识解释（用途/常规用法用量/常见不良反应/常见注意事项四节）；不接收也不使用任何患者档案字段；禁止个性化剂量与替代药建议；结尾注入契约免责声明；不认识的药输出契约 `unknown_drug` 话术
- [x] TestClient + fake LLM：断言 token 顺序、done 事件、免责声明注入、`unknown_drug` 话术、空药名 422

## 4. server-java 节

- [x] WS 层识别 `chat` 信封 `medication_name`：入口审计（只记脱敏药名，硬约束 5）→ 调 server-py 新端点 → token 经 `event` 信封透传（复用票 33/40 relay 机制）→ 轮次落 `KIND_TEXT`；流尾出口兜底免责声明并追加 `consult_professional`（硬约束 1）
- [x] `MedicationLookupService` 删除 `matchMedications`、`MedicationMapper` 双列查询、`HealthProfileAllergyMapper`、`ContraindicationFactRepository`、`ContraindicationRuleEngine` 依赖与 reminder 逻辑；双列查询方法若仅本链路引用则删除（先核对 B 端药品管理引用）
- [x] 删除 `POST /api/c/medication-lookups`；`/c/pill-box-photos` 响应改 `{request_id, conversation_id, recognized, drug_names[], hint?}`，不再回卡片；`MedicationLookupView` 相应改/删；未识别到药名与非药盒拒绝的 hint 文案维持现状
- [x] MinIO 旁路持久化改为与 vision 调用并行（best-effort 语义不变，ADR-0023；原「网络调用不在事务内」等注释同步更新）
- [x] `rule/` 包、`ContraindicationService`、B 端处方链路不动；MockMvc/WS 测试断言：拍药盒响应无卡片含药名、流尾双话术、`HealthProfileService`/Neo4j/规则引擎 zero interaction（mock verify）；B 端禁忌测试原样通过

## 5. 小程序节

- [x] `pillbox-composer.js`：上传成功后按 `drug_names[0]` 自动发送 `medication_name` 信封；进度文案分级（「正在识别药名…」→「正在生成药品说明…」）；多候选追加文本提示「还识别到：X、Y，可直接输入药名查看」；流式渲染复用主对话 text 气泡
- [x] 隐藏「查药品」入口并删除 `medication-lookup-composer.js`
- [x] 删除 `components/medication-info-card`、`components/medication-safety-card` 及其在 `index.axml`、`message-kinds.js`、`drawer.js` 的引用
- [x] 上传前压缩图片（`my.compressImage` 或 chooseMedia `sizeType: ['compressed']`，长边 ≤1600px）

## 6. 文档收口节

- [x] 新 ADR-0028《C 端药品说明收口为通用知识流，个性化禁忌仅留 B 端开方》：取代 ADR-0025 决策 1（双列查）/2（C 端规则引擎组装）/3（双出口卡片）；ADR-0025 标 partially superseded（vision 纯 OCR 提名与 MinIO 旁路仍有效）；重申 ADR-0016；写入 C 端处方解读原则（只解读医生已批准处方的药名/用法/医生注明注意事项，不重新替医生做安全判定）
- [x] `spec.md`：16 条改写为 B 端开方禁忌场景；36 条删除「含过敏禁忌提醒」
- [x] `CONTEXT.md`：重写「药品查询」词条为收口后语义；「通用药品知识解释」补充拍药盒即该形态；检查「会话」词条中过敏禁忌拦截表述
- [x] 票 14 Comments 追加「C 端个性化安全部分被票 51 取代，依据 ADR-0028」；票 11 不动（B 端底座）；AGENTS.md 若残留双出口描述则同步

## 7. 验证与收口

- [x] `mvn -f server-java/pom.xml test`、`spotless:check`、`uv run pytest`、`uv run ruff check server-py`、`uv run mypy server-py/app`、`uv run lint-imports` 全绿
- [ ] 支付宝开发者工具人工走查三态：可识别药盒（流式说明书 + 流尾话术 + 免责声明）、非药盒照片（scope 拒绝提示）、模糊照片（未识别引导），无控制台错误
- [ ] 性能前后计时记入 Comments；票单 checklist 同步更新；置 `done` 前 README 节点更新为 `T51["[x]51 拍药盒收口与说明书流"]`（节点与边已建，待人工走查通过后加 `[x]`）

## Comments

- 2026-08-06（决策）：用户收口判断——禁忌内核保留 B 端（票 11 底座），删除 C 端个性化安全出口，复杂性封装在 B 端开方 seam 之后；演示主线「导诊 → 挂号 → 接诊 → 开方 → 处方解读」，禁忌作为 B 端 30–60 秒可选加分支线。grill 结论：范围合并为 1 票（项目历史粒度为大票，一票一分支），性能 quick win 票内先行 commit。说明书走流式文本而非卡片：卡片在本系统从不流式，说明书是长文本，流式文本体验与前端成本均更优。
- 2026-08-06（§1 计时）：本地单进程 server-py + 同一药盒照片（.scratch/t46-pillbox.png）端到端对比——改动前（reasoning_effort=high）42.3s / 87.7s 两次；改动后（PILL_BOX=disabled）5.8s / 3.5s 两次。识别结果一致（均提名「阿莫西林胶囊」），提速约 10 倍。
- 2026-08-06（§6 文档收口）：新增 ADR-0028 并将 ADR-0025 标 partially superseded；spec.md 16 条改写为 B 端开方禁忌场景、36 条删除「含过敏禁忌提醒」；CONTEXT.md 重写「药品查询」词条、补充「通用药品知识解释」与「会话」词条；票 14 Comments 已追加取代说明；AGENTS.md 无双出口残留，未改。
- 2026-08-06（偏离说明）：§4 checklist 要求「未识别到药名与非药盒拒绝的 hint 文案维持现状」，但原文案含「使用『查药品』入口」的死引用（该入口本票已删），hint 已改为引导「直接输入药名」；其余语义不变。
- 2026-08-06（§5 压缩说明）：`pillbox-picker.js` 票 14 已接入 `my.compressImage`（compressLevel 2），满足「上传前压缩」；长边归一化由 server-py 视觉管道 2048px 兜底，本票未改压缩代码。
- 2026-08-06（§7 自动化验证）：mvn 全量测试 339 个全绿（BUILD SUCCESS）、spotless:check 绿、uv run pytest 146 绿、ruff 绿、lint-imports 3 kept 0 broken、mypy 仅 2 个 Windows 平台预存错误（main.py/seed_embeddings.py 的 WindowsSelectorEventLoopPolicy，基线已有）。/code-review 两轴审查后修复：WS 信封互斥校验对齐 HTTP 的 XOR 规则并补「两空拒绝」测试、javadoc 错别字。README 依赖图已建 T51 节点与 T14/T46 边；因前端验收需支付宝开发者工具人工三态走查（§7 item 2），票暂置 claimed，走查通过后加 `[x]` 并置 done。
- 2026-08-07（票 53 调整）：说明书的固定四节排版（用途/常规用法用量/常见不良反应/常见注意事项）由票 53 修订为「摘要 + 2～4 个自由章节 + 安全提示」的半结构化 Markdown；药品查询的安全与架构边界（通用知识、无档案读取、禁忌仅留 B 端、免责声明双端注入）未改变。
