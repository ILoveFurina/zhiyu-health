# 在线问诊交流媒体消息：患者图片 + 语音输入，复用 AI 对话模块能力

Status: accepted（在线问诊图文交流扩展，2026-08-08 grilling 会话定型）

在线问诊交流原为纯文字：`online_consultation_messages` 无 kind 列，患者既不能发症状照片也无法语音输入；而 AI 对话模块已具备成熟的图片管道（选图/压缩/知情同意 → `my.uploadFile` → MinIO 旁路持久化 → `image` kind 消息 → 鉴权代理回看）与语音输入（ADR-0020 的 ASR seam）。决策：**在线问诊图文交流复用 AI 对话模块的图片与语音输入能力**，形成"患者可发图 + 语音输入，医生只读看图 + 文字回复"的单向媒体形态；语音只作输入通道、不是消息类型。

## 决策

1. **消息模型**：`online_consultation_messages` 加 `kind` 列（CHECK `text`/`image`，默认 `text`）；图片消息 `content` 存 `{"object_key","media_type"}` JSON——与 `messages` 表 `image` kind 约定同构。`MessageView` 加 `kind` 字段，`contracts/online-consultation.json` 加 `message_kinds`，双端镜像同步。
2. **图片管道**：患者新端点 `POST /c/online-consultations/{id}/photos`（multipart，患者鉴权，状态守卫与文字消息同构——仅医生已接受的进行中问诊可发，完成后只读）→ `MinioStorageService.storePhoto` 旁路持久化 → 写 `image` 消息。回看复用既有双通道：C 端 `/api/c/photos`（患者侧）、B 端 `/api/b/photos`（票 54 先例，admin 鉴权，object_key 即凭证）。
3. **语音输入**：点亮 `contracts/voice.json` `asr_enabled`（`tts_enabled` 保持 false）；server-py `voice.py` 增加"enabled 但无火山密钥 → Fake"回落分支（否则 `enabled=true` 会撞 `VolcAsrClient` 的 `NotImplementedError`）。问诊页复用 chat 页 recorder + `utils/voice.js`，识别文字回填输入框、可编辑后发送——语音不落任何消息类型。
4. **医生端只读**：`OnlineConsultationDrawer` 渲染 `image` 消息（antd `Image` + `/api/b/photos` 代理 + 预览），无上传与语音能力，回复保持纯文字。
5. **图片是消息本体**：图片发送失败（含 MinIO 不可用）即发送失败，前端提示重试，不降级落库——与拍照分析"MinIO 失败不落图但分析照常"不同，因为问诊图片没有可替代的产出物。

## 为什么复用而非另建

- 选图/压缩/知情同意模式、`MinioStorageService`、`/api/b/photos` 代理、ADR-0020 ASR seam 均为页面无关通用件，直接复用；
- **不复用** 5 个场景上传端点（皮肤/饮食/舌苔/药盒/报告）：它们绑定各自视觉分析场景（上传即触发 LLM），问诊只需"存 + 传"；
- 语音只做输入通道（ASR→文字），**不做语音消息**：AI 模块无此形态，等于新造功能，且问诊记录保持文字可读可审计。

## 被否决的方案

- **即用即弃图片（报告解读先例）**：图片是交流内容本身，无"解读产物"可替代；医生异步回看与"问诊完成后只读"会破图，且需新建暂存回拉通道。ADR-0023 已否决过"暂存可过期"。
- **医生端双向媒体**：B 端为 React 技术栈需另建上传/录音，演示范围已冻结，按票后交付单向最小形态。
- **本轮接入真实火山 ASR**：`.env` 无语音密钥，Volc 实现未开通，超出"复用"范畴；Fake 阶段（固定文本回填可编辑）演示交互完整。

## Consequences

- `schema.sql` 加列 + 契约双端同步（`message_kinds`、`asr_enabled`、新 `consultation-photo-limits.json`），`ContractsConsistencyTest` 钉死；
- **`asr_enabled` 全局生效**：AI 对话页的按住说话按钮随之点亮——AI 对话与在线问诊的语音输入同时上线（副作用，演示可作支线）；
- AGENTS.md 硬约束 3 与顶部存储行文本随 ADR-0023/0029 扩展史修正；
- 问诊完成后图片仍可回看，MinIO 孤儿对象清理与既有 `image` 消息同策略（两周 demo 可接受）；
- 问诊图片为病情沟通材料，隐私敏感度高于拍照分析的生活影像，仍选持久化——回看价值与"完成后只读"语义优先，本 ADR 记录该权衡（与报告解读的即用即弃形成第三种场景语义）。
