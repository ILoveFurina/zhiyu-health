# 58 - 在线问诊交流媒体消息：患者图片 + 语音输入（复用 AI 对话模块能力）

**What to build:** 在线问诊图文交流支持患者发送图片（每次一张，MinIO 旁路持久化）与语音输入（按住说话 → ASR 转文字回填输入框，可编辑后发送）；医生端只读查看图片、保持纯文字回复。复用 AI 对话模块的选图/压缩/知情同意模式、`MinioStorageService`、C/B 双通道回看与 ADR-0020 ASR seam；语音点亮采用 Fake（当前方舟 key 无图像生成权限，且语音 seam 本就是 Fake 先行设计）。设计定型于 2026-08-08 grilling 会话（ADR-0029）。

**Blocked by:** 无（消息模型、MinIO 写路径与 /api/b/photos 回看先例已由票 55/54 + ADR-0023 落地）

**Status:** claimed

- [x] `contracts/online-consultation.json` 新增 `message_kinds`（text/image）与 `_doc`；`contracts/voice.json` `asr_enabled=true`（`tts_enabled` 保持 false，`asr_format` 保持 null）；新增 `contracts/consultation-photo-limits.json`（JPEG/PNG、≤2MB，与 doctor-photo-limits 同值）
- [x] `ContractsConsistencyTest` 同步：`voiceContractSkeletonIsLoaded` 改为断言 asr 已点亮、tts 未点亮；新增 `online_consultation_messages.kind` CHECK 覆盖 `message_kinds` 的断言
- [x] `schema.sql`：`online_consultation_messages` 加 `kind VARCHAR(10) NOT NULL DEFAULT 'text'` + CHECK（text/image）+ COMMENT；完成后跑 `reset_zhiyu.py` + `verify_zhiyu.py` 重建云演示库
- [x] server-java：`OnlineConsultationMessage` 实体加 `kind` 字段；`MessageView` 加 `kind`；`appendMessage` 支持 kind；新增 `sendImageForPatient(patientId, id, MultipartFile)`（复用 `requireOwnedByPatient`/`requireInProgress`/`requireMethodInitiated` 守卫，`storePhoto` → 写 `kind=image`、content JSON `{"object_key","media_type"}` 消息）；MinIO 失败抛 ApiException（图片即消息本体，不降级）
- [x] server-java：新端点 `POST /c/online-consultations/{id}/photos`（患者鉴权，multipart，文件类型/大小校验读 `consultation-photo-limits.json`，controller 只做校验与装配）
- [x] server-py：`voice.py` 增加"enabled 但无火山密钥 → Fake"回落分支（否则 `asr_enabled=true` 会撞 `VolcAsrClient.NotImplementedError`）
- [x] miniprogram：`utils/consultation.js` 镜像加 `MESSAGE_KINDS`；`services/consultation.js` 新增 `uploadPhoto(id, path)`（`my.uploadFile` → `/c/online-consultations/{id}/photos`）
- [x] miniprogram `pages/consult/doctor`：输入栏加图片按钮（复用选图/压缩/知情同意模式，每次 1 张）与按住说话按钮（`asr_enabled` 控制渲染，复用 chat 页 recorder 逻辑 + `utils/voice.js`）；消息流按 `kind` 渲染 image 气泡（`/c/photos?key=` + 全屏预览）与 text 气泡
- [x] admin `OnlineConsultationDrawer`：`kind=image` 消息渲染 antd `Image`（`/api/b/photos?key=` + 预览），医生端无上传/语音能力
- [x] 测试（新票从简分层）：server-java service 单测（图片发送守卫：非进行中/未发起方式/非本人/完成后拒绝；成功落 image 消息与 content JSON）+ MockMvc 上传→201→返回 image 消息的主链路冒烟；server-py 语音回落分支单测（无密钥 → Fake 固定文本，原"未配置 503"用例改语义）
- [ ] 支付宝开发者工具与浏览器人工走通"问诊中 → 患者发图 → 医生端查看 → 患者语音输入转文字 → 医生文字回复"，两端无红色控制台错误
- [ ] 完成前更新 ADR-0029 施工记录、票单 checklist，并在 README 依赖图将 58 节点标记为 `[x]58`

实施边界：不做语音消息（录音作为消息发送）、不做医生端上传/语音（医生保持纯文字）、不做真实火山 ASR 接入（Fake 阶段）、不进演示剧本冻结清单。`asr_enabled` 全局生效的副作用（AI 对话页语音按钮同步点亮）为预期行为，不在本票消除。

## Comments

- 2026-08-08 施工记录（分支 t58-online-consultation-media）：分层提交（docs → contracts/server-java/server-py/miniprogram/admin）。server-java 585 测试 + spotless 绿；server-py 契约/语音测试 24 绿（`test_knowledge_integration` 两条为本机连云 pgvector/方舟 embedding 的既有环境问题，与票 55 注释一致，与本票无关）；admin typecheck 绿；小程序 node --check 绿。云演示库已按 AGENTS.md 运行拓扑重建（`reset_zhiyu.py` + `verify_zhiyu.py` 全项通过，kind 列已就位）。
- 待人工：支付宝开发者工具与浏览器端到端走通后，勾掉最后两项 checklist、置 done 并在 README 依赖图标记 `[x]58`。`asr_enabled` 点亮为全局生效，AI 对话页按住说话按钮同步出现（预期副作用，演示可作支线）。
