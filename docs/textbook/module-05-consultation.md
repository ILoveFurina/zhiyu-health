# 模块5：在线问诊

## 业务概述

在线问诊是本项目中跨度最大的端到端闭环：患者（C 端小程序）先与 AI 完成预问诊对话，AI 在后台整理出结构化病情摘要（主诉/现病史/过敏史 + 受控建议标准科室），患者确认摘要后创建问诊单进入对应科室的待接诊池；医生在 B 端工作台抢单接诊、发起图文/模拟视频接诊方式，医患双发文字与图片消息，最后医生填写诊断结论与医嘱完成问诊，并可开具电子处方。整个链路贯穿小程序、server-java（唯一业务写入方）、server-py（唯一 LLM 调用方）与 admin 四端，状态机、五步进度、文案均以 `contracts/online-consultation.json` 为单一事实源。

## 业务流程

1. **入口分发**：C 端进入 `pages/consult/entry`（入口路由页，不承载表单）。无当前健康档案 → 引导建档；当前档案有 `WAITING_DOCTOR`/`IN_PROGRESS` 问诊 → 直接续接到等待页或医生问诊页；否则调 `POST /c/preconsultation-drafts` 开始/恢复预问诊草稿。
2. **AI 预问诊**：`pages/consult/preconsult` 复用 AI 对话模块的 WS/SSE 通道，每轮对话携带 `preconsultation_draft_id`，server-java 校验草稿归属与状态后强制 `preconsultation` 场景转发 server-py。预问诊场景不暴露任何业务工具，只做病情收集对话；红线症状仍由 server-java 在入口确定性判断（规则引擎先于 LLM）。
3. **摘要异步整理与回调**：每轮对话的 `message`/`done` 事件先交付（客户端输入框立即解锁），server-py 随后后台发起第二次非流式 LLM 调用（judge），把对话整理为结构化摘要，经鉴权 HTTP 回调 `POST /api/agent/preconsultation-drafts/{id}/summary` 落草稿；C 端轮询回拉草稿，摘要就绪后亮起「查看病情摘要并确认」CTA。
4. **确认建单**：患者在 `pages/consult/summary` 查看摘要快照与建议科室，确认后 `POST /c/online-consultations` 创建问诊单：摘要快照从草稿拷贝固化，状态 `WAITING_DOCTOR`，接诊截止时间 = 创建时间 + 契约 `accept_timeout_seconds`（600s）。
5. **等待接诊**：`pages/consult/waiting` 每 3s 轮询问诊单详情。超时无人接单由 server-java 在查询/操作入口**惰性收敛**为 `EXPIRED`（不引入调度中间件）；患者可主动取消；`CANCELLED`/`EXPIRED` 均可复用原摘要重新提交（新单新 id）。
6. **医生接诊**：B 端工作台「待接诊池」每 10s 轮询本科室 `WAITING_DOCTOR` 单，医生打开抽屉查看档案、过敏史与 AI 病情摘要后点「接受问诊」——条件 UPDATE 保证并发抢单只有一方成功，状态翻 `IN_PROGRESS` 并写系统消息。
7. **发起接诊方式**：医生在 `IN_PROGRESS` 阶段明确发起图文（TEXT）或模拟视频（VIDEO，纯 UI 不接真实音视频）；方式未发起前双方都不能发消息（server-java 409 兜底）。接诊起算固定时长窗（契约 `consultation_duration_seconds` 1800s），双端各自倒计时，到期同样惰性收敛 `EXPIRED`。
8. **医患沟通**：双发文字消息（`after_id` 增量轮询）；患者可发图片（知情同意 → 压缩 → multipart 上传 → MinIO 旁路 → `image` kind 消息），医生只读回看（B 端经 `/api/b/reception/photos` 鉴权代理 fetch blob）；患者还可语音输入（ASR 转文字回填输入框，语音不构成消息类型）。
9. **完成问诊**：医生填写诊断结论与医嘱提交完成——状态翻转、接诊记录（`consultation_records`）、系统消息、随访关怀站内信在同一事务提交；进行中医生还可开具电子处方（进入处方审核流）。患者侧完成后展示只读诊断卡与处方出口。

## 代码地图

| 层 | 职责 | 文件路径 |
| --- | --- | --- |
| 契约 | 状态机/草稿状态/五步进度/接诊方式/消息类型/文案/超时的单一事实源 | `contracts/online-consultation.json` |
| 契约 | 问诊图片大小与类型上限（≤2MB、JPEG/PNG、单张） | `contracts/consultation-photo-limits.json` |
| 小程序页面 | 入口路由分发（建档引导/续接/开始草稿） | `miniprogram/pages/consult/entry/index.js` |
| 小程序页面 | AI 预问诊对话页（复用 chat 通道，摘要轮询） | `miniprogram/pages/consult/preconsult/index.js` |
| 小程序页面 | 病情摘要确认页（确认建单） | `miniprogram/pages/consult/summary/index.js` |
| 小程序页面 | 等待接诊页（3s 轮询 + 倒计时 + 取消/重提） | `miniprogram/pages/consult/waiting/index.js` |
| 小程序页面 | 医生问诊页（消息/图片/语音/倒计时/结束） | `miniprogram/pages/consult/doctor/index.js` |
| 小程序服务 | 预问诊草稿与问诊单 REST 封装、图片上传 | `miniprogram/services/consultation.js` |
| 小程序常量 | 契约 JSON 的端侧本地镜像（状态/步骤/文案） | `miniprogram/utils/consultation.js` |
| 小程序通道 | WS 连接内 auth 首帧鉴权 + SSE 降级 | `miniprogram/utils/chat-stream.js` |
| admin 页面 | 待接诊池/进行中/已完成三 Tab + 池轮询 | `admin/src/pages/Workbench/components/OnlineConsultationPanel.tsx` |
| admin 页面 | 问诊抽屉（接诊/方式/消息/开方/完成） | `admin/src/pages/Workbench/components/OnlineConsultationDrawer.tsx` |
| admin 页面 | 今日挂号队列（线下接诊台，与本模块并列的兄弟面板） | `admin/src/pages/Workbench/components/ReceptionQueue.tsx` |
| server-java controller | C 端问诊单接口（`/api/c/online-consultations/**`） | `server-java/src/main/java/com/zhiyu/health/controller/patient/consultation/OnlineConsultationController.java` |
| server-java controller | C 端预问诊草稿接口（`/api/c/preconsultation-drafts/**`） | `server-java/src/main/java/com/zhiyu/health/controller/patient/chat/PreconsultationController.java` |
| server-java controller | B 端接诊接口（`/api/b/reception/online-consultations/**`） | `server-java/src/main/java/com/zhiyu/health/controller/staff/consultation/OnlineConsultationController.java` |
| server-java controller | 预问诊摘要回调承接（`/api/agent/preconsultation-drafts/**`） | `server-java/src/main/java/com/zhiyu/health/controller/agent/PreconsultationSummaryCallbackController.java` |
| server-java controller | C 端对话 WebSocket 传输适配器（连接内 auth 首帧） | `server-java/src/main/java/com/zhiyu/health/controller/patient/chat/ChatWebSocketHandler.java` |
| server-java config | WS 端点注册 + 握手兼容拦截器 | `server-java/src/main/java/com/zhiyu/health/config/ChatWebSocketConfig.java`、`ChatWebSocketHandshakeInterceptor.java` |
| server-java config | `/api/agent/**` 回调令牌校验（X-Agent-Callback-Token） | `server-java/src/main/java/com/zhiyu/health/config/AgentCallbackAuthFilter.java` |
| server-java service | 问诊稳定门面（组合双侧 workflow） | `server-java/src/main/java/com/zhiyu/health/service/consultation/OnlineConsultationService.java` |
| server-java service | 患者侧生命周期（确认建单/取消/重提/结束/消息） | `server-java/src/main/java/com/zhiyu/health/service/consultation/PatientOnlineConsultationWorkflow.java` |
| server-java service | 医生侧工作台（科室池/抢单/接诊方式/完成/随访） | `server-java/src/main/java/com/zhiyu/health/service/consultation/DoctorOnlineConsultationWorkflow.java` |
| server-java service | 消息持久化与图片 MinIO 旁路 | `server-java/src/main/java/com/zhiyu/health/service/consultation/OnlineConsultationMessaging.java` |
| server-java service | 双端共享身份/状态守卫与惰性收敛 | `server-java/src/main/java/com/zhiyu/health/service/consultation/OnlineConsultationAccess.java` |
| server-java service | 预问诊草稿生命周期与摘要落库复检 | `server-java/src/main/java/com/zhiyu/health/service/chat/PreconsultationService.java` |
| server-java service | 线下接诊台（挂号叫号/接诊，与在线问诊池并列） | `server-java/src/main/java/com/zhiyu/health/service/consultation/ReceptionService.java` |
| server-py service | 摘要后台调度：done 后异步 judge + 回调 | `server-py/app/services/chat_preconsultation.py` |
| server-py agent | 摘要 judge：json_object + pydantic 校验 + 科室受控归一 | `server-py/app/agent/preconsult.py` |
| server-py tools | 摘要回调适配器（POST summary 到 server-java） | `server-py/app/tools/preconsult_callback.py` |
| server-py tools | 业务回调通道与统一鉴权头/失败语义 | `server-py/app/tools/callback.py` |

## 核心代码走读

### 5.1 双侧工作流：门面 + 患者/医生两个 workflow

`OnlineConsultationService` 不直接承载逻辑，而是一个稳定门面：构造期组装共享的 `OnlineConsultationMessaging`（消息与图片旁路）与 `OnlineConsultationAccess`（身份/状态守卫），再把患者生命周期与医生工作台分别委托给两个 package-private workflow（`OnlineConsultationService.java:41-72`）：

```java
        OnlineConsultationMessaging messaging =
                new OnlineConsultationMessaging(messageMapper, dtoMapper, contracts, minioStorage, objectMapper);
        OnlineConsultationAccess access =
                new OnlineConsultationAccess(consultationMapper, staffUserMapper, allergyMapper, contracts, dtoMapper);
        this.patient = new PatientOnlineConsultationWorkflow(
                consultationMapper, draftMapper, transactionTemplate, contracts, dtoMapper, messaging, access);
        this.doctor = new DoctorOnlineConsultationWorkflow(
                consultationMapper,
                consultationRecordMapper,
                transactionTemplate,
                contracts,
                messaging,
                prescriptionMapper,
                inAppMessageMapper,
                disclaimers,
                access);
```

这是「一个文件只承担一个职责」的典型拆分：C 端 `/api/c/online-consultations/**` 与 B 端 `/api/b/reception/online-consultations/**` 两个 controller 都只装配身份后调门面，患者侧能做什么（确认/取消/重提/结束/发消息）与医生侧能做什么（抢单/发起方式/完成/开方）各自收敛在一个 workflow 里，共享守卫不重复。注意 `ReceptionService` 虽然同包，但它服务的是**线下接诊台**（今日挂号队列的叫号/接诊，对应 admin 的 `ReceptionQueue`），与在线问诊待接诊池是两条业务线，不要混淆。

患者侧的状态迁移全是**条件 UPDATE**而非先查后改。以医生抢单为例（`DoctorOnlineConsultationWorkflow.java:103-115`）：

```java
        return transactions.execute(status -> {
            access.expireOverdue();
            if (mapper.accept(id, doctorId, access.waiting(), access.inProgress()) != 1) {
                throw new ApiException(409, access.text("accept_conflict"));
            }
            messaging.append(
                    id,
                    access.senderType("system"),
                    OnlineConsultationMessage.KIND_TEXT,
                    access.text("doctor_accepted"));
            logDecision("accept", id, doctorId);
            return access.doctorDetail(mapper.selectDetailedById(id));
        });
```

`accept` 是一条带 `WHERE status='WAITING_DOCTOR' AND doctor_id IS NULL AND 未过期` 谓词的条件更新，影响行数 ≠1 即说明单子已被别的医生抢走或已过期，抛 409（文案取自契约 `texts.accept_conflict`）；状态翻转与「医生已接受问诊」系统消息在同一事务提交。患者侧 `confirm`（建单与草稿提交同事务，`PatientOnlineConsultationWorkflow.java:52-87`）、`cancel/end` 同样遵循这个模式。

另一个贯穿双端的机制是**惰性收敛**（`OnlineConsultationAccess.java:99-109`）：

```java
    void expireOverdue() {
        mapper.expireOverdue(waiting(), expired());
        // 票 86 时长窗惰性收敛与接诊超时同一入口：所有调用点一次调用两种收敛同时生效
        mapper.expireInProgressOverdue(
                inProgress(),
                expired(),
                contracts.onlineConsultation().consultationDurationSeconds(),
                senderType("system"),
                OnlineConsultationMessage.KIND_TEXT,
                text("duration_expired"));
    }
```

接诊超时（600s）与问诊时长窗（1800s）都不依赖定时任务：任何查询/操作入口先调 `expireOverdue()` 把到期单翻成 `EXPIRED`（时长窗到期还顺手补一条系统消息），再做后续状态守卫。这保证「过期的单子不可再操作」这一不变量在所有入口同时成立，而双端 UI 上的倒计时（小程序 `doctor/index.js:140-168` 逐秒倒数、admin 抽屉 `OnlineConsultationDrawer.tsx:186-199`）只是展示层，终态权威始终在后端。

### 5.2 WS 连接内 auth 首帧鉴权（支付宝 IDE 剥 header 的坑）

预问诊对话复用 AI 对话模块的 WebSocket 通道。这里有一个真实的平台坑：支付宝开发者工具会给 `my.connectSocket` 的自定义 header 值包上字面双引号，而 cpolar 隧道又会重建 Upgrade 请求并剥掉自定义头——**鉴权信息不能走 WS 握手**。因此 JWT 改在连接建立后的首帧传输（`miniprogram/utils/chat-stream.js:21-35`）：

```javascript
  const onOpen = () => {
    open = true
    // 隧道可能重建 upgrade 并剥掉自定义 header；JWT 改在连接建立后的首帧传输，
    // authenticated 到达前 connect Promise 不完成，chat 不会抢在认证前发送。
    authTimer = setTimeout(() => {
      authTimer = null
      const shouldClose = open
      onError({ message: 'WebSocket 认证超时' })
      if (shouldClose) my.closeSocket()
    }, WS_AUTH_TIMEOUT_MS)
    my.sendSocketMessage({
      data: JSON.stringify({ type: 'auth', data: { token: getToken() } }),
      fail: (detail) => onError(detail),
    })
  }
```

客户端发 `auth` 信封后启动 5s 看门狗，收到 `authenticated` 前 `connect()` 的 Promise 不兑现，`chat` 信封不可能抢在认证之前发出（`chat-stream.js:44-52`）。JWT 也绝不进 URL（避免进代理日志）。

server-java 侧对应两段。握手拦截器只做兼容：直连请求若已被 `AuthFilter` 注入可信身份则透传，否则一律放行（`ChatWebSocketHandshakeInterceptor.java:26-38`），把鉴权留给连接内首帧。`ChatWebSocketHandler` 里会话状态 `patientId == null` 时只接受 `auth` 信封（`ChatWebSocketHandler.java:136-159`）：

```java
        try {
            AuthPayload data = objectMapper.treeToValue(envelope.data(), AuthPayload.class);
            if (patientTokens == null) {
                throw new IllegalStateException("患者令牌校验器不可用");
            }
            state.patientId = patientTokens.verify(data.token());
            send(
                    state.session,
                    new OutgoingEnvelope(
                            contracts.chatRealtime().authenticatedEnvelope(),
                            null,
                            null,
                            objectMapper.createObjectNode().put("status", "ok")));
        } catch (Exception error) {
            sendError(state.session, null, "AUTH_INVALID", "WebSocket 认证失败");
            closePolicyViolation(rawSession);
        }
```

认证失败立即以 `POLICY_VIOLATION` 关闭连接；信封类型名（`auth`/`authenticated`/`chat`/`event`/`error`）全部来自 `contracts/chat-realtime.json`（`ChatWebSocketConfig.java:20-24` 注册的端点路径也取自契约）。认证后的 `chat` 信封可携带 `preconsultation_draft_id`（`ChatWebSocketHandler.java:237-238` 的 `ChatPayload` 字段），server-java 校验草稿归属与状态后强制预问诊场景——**场景权限由后端校验草稿授予，客户端不能自由指定 `preconsultation` 场景获得预问诊能力**。

### 5.3 工具调用/回调：预问诊摘要 judge 如何回调 server-java

先澄清一个容易误解的点：**摘要 judge 不是 LangGraph 工具**。`app/agent/runner.py` 中业务工具（医生推荐/号源/挂号等）经 `create_agent(model, tools=..., middleware=...)` 注册进 LangGraph，但 `_tools_for()` 对预问诊场景做了工具隔离（`runner.py:134-144`）：

```python
    def _tools_for(self, knowledge_source: str, scenario: str) -> list[BaseTool]:
        # 工具隔离：预问诊场景不暴露任何业务工具（医生推荐/号源/挂号），
        # 隔离由编排代码保证而非提示词；知识工具仍按 knowledge_source 注入。
        # rag 态注入 search_knowledge；graph 态注入 traverse_graph（互斥）；
        # none/其他不注入（LLM 看不到即不检索）
        base = [] if scenario == _PRECONSULT_SCENARIO else self._base_tools
        if knowledge_source == "rag" and self._knowledge_tools:
            return [*base, *self._knowledge_tools]
        if knowledge_source == "graph" and self._graph_tools:
            return [*base, *self._graph_tools]
        return list(base)
```

预问诊场景下 `base` 为空列表，LLM 在编排层就看不到任何业务工具（隔离靠代码而非提示词）。真正的摘要链路是一条**图外串行二次 LLM 调用 + 鉴权 HTTP 回调**，分四步：

第一步，调度。`ChatService` 在 `message`/`done` 事件交付之后才触发摘要任务（`server-py/app/services/chat.py:132-140`）：

```python
        # 7. 摘要不在关键路径：done 已让客户端解锁，失败只保留 server-java 上一版草稿。
        if scenario == _PRECONSULTATION_SCENARIO and preconsultation_draft_id is not None:
            self._last_summary_task = self._preconsultation.schedule(
                preconsultation_draft_id,
                messages,
                "".join(parts),
                longitude,
                latitude,
            )
```

第二步，judge。`StructuredPreconsultJudge.judge()` 发起非流式调用（`response_format=json_object`、关闭思考、15s 超时、零网络重试），pydantic 校验失败最多重试 2 次；任何失败一律返回 `None` 降级——本轮省略快照，草稿保留上一版（`server-py/app/agent/preconsult.py:82-114`）。其中 `_normalize()` 是科室受控解析的关键（`preconsult.py:59-69`）：建议科室 ID 必须落在候选标准科室目录内，目录外/臆造的 ID 一律归一化为 `None`，LLM 不得编造。

第三步，回调。`PreconsultationSummaryCallback.apply()` 把摘要 POST 给 server-java（`server-py/app/tools/preconsult_callback.py:25-35`）：

```python
    async def apply(self, draft_id: int, payload: dict[str, Any]) -> None:
        try:
            await self._client.post(
                f"/api/agent/preconsultation-drafts/{draft_id}/summary", payload
            )
        except httpx.HTTPError as error:
            logger.warning(
                "preconsultation summary callback failed draftId=%s error=%s",
                draft_id,
                error.__class__.__name__,
            )
```

回调失败只记日志（且不记摘要原文，只记草稿 ID 和异常类型），不连坐已完成的对话流。底层 `BusinessCallbackClient`（`server-py/app/tools/callback.py:22-28`）在构造时把共享密钥放进 `X-Agent-Callback-Token` 头，并 `trust_env=False` 绕过系统代理：

```python
        self._client = httpx.AsyncClient(
            base_url=base_url,
            timeout=timeout,
            transport=transport,
            trust_env=False,
            headers={"X-Agent-Callback-Token": callback_secret} if callback_secret else None,
        )
```

第四步，承接与复检。server-java 侧 `AgentCallbackAuthFilter`（`AgentCallbackAuthFilter.java:30-37`）对 `/api/agent/**` 用 `MessageDigest.isEqual` 恒定时间比对令牌，不匹配直接 401；通过后进入 `PreconsultationSummaryCallbackController.applySummary()`（`PreconsultationSummaryCallbackController.java:33-37`），委托 `PreconsultationService.applySummary()` 落库（`PreconsultationService.java:102-116`）：

```java
    public void applySummary(long draftId, JsonNode payload) {
        String chiefComplaint = textOrNull(payload.get("chief_complaint"));
        String presentIllness = textOrNull(payload.get("present_illness"));
        if (chiefComplaint == null || presentIllness == null) {
            return;
        }
        String allergyHistory = textOrNull(payload.get("allergy_history"));
        Long departmentId = null;
        JsonNode departmentNode = payload.get("suggested_standard_department_id");
        if (departmentNode != null && departmentNode.isIntegralNumber()) {
            long candidate = departmentNode.asLong();
            if (standardDepartmentMapper.selectById(candidate) != null) {
                departmentId = candidate;
            }
        }
```

注意 server-java **不信任模型输出**：主诉/现病史缺失视为不可用快照直接保留上一版；科室 ID 在 server-py 受控归一之后，这里再查一次标准科室目录复检；免责声明字段不采用模型回传值，由本端统一兜底。条件更新命中 0 行（草稿已被并发提交）静默返回——幂等旁路语义。

### 5.4 确认建单：草稿到问诊单的原子迁移

患者确认摘要后，`PatientOnlineConsultationWorkflow.confirm()` 在一个事务里完成「插问诊单 + 草稿标记已提交」（`PatientOnlineConsultationWorkflow.java:70-87`）：

```java
        try {
            return transactions.execute(status -> {
                OnlineConsultation consultation = fromDraft(patientId, draft);
                mapper.insert(consultation);
                if (drafts.markSubmitted(draft.getId(), submitted(), collecting(), pendingConfirm()) != 1) {
                    // 并发确认输家回滚整个事务，不能留下没有对应提交草稿的孤立问诊单。
                    throw new IllegalStateException("预问诊草稿提交失败 draftId=" + draft.getId());
                }
                return access.patientDetail(mapper.selectDetailedById(consultation.getId()));
            });
        } catch (DataIntegrityViolationException e) {
            OnlineConsultation active = access.activeByProfile(draft.getHealthProfileId());
            if (active != null) {
                return access.patientDetail(active);
            }
            throw e;
        }
```

两道并发防线：草稿的 `markSubmitted` 是带 `WHERE status IN (COLLECTING, PENDING_CONFIRM)` 的条件更新，并发确认的输家影响行数为 0，抛异常回滚整个事务（不会留下孤立问诊单）；数据库层「每份健康档案同时最多一条活跃问诊」由部分唯一索引保证（契约 `active_statuses`），撞索引时回放已存在的活跃单返回——重复确认是幂等的。摘要三字段与免责声明在建单时从草稿**拷贝固化**到问诊单（`fromDraft`，`PatientOnlineConsultationWorkflow.java:179-192`），此后摘要不再可变。

### 5.5 图片消息与 MinIO 旁路（ADR-0029）

患者发图是「消息本体即图片」：小程序端知情同意（首次弹窗记 storage）→ 压缩 → `my.uploadFile` multipart 上传（`services/consultation.js:86-105`）；server-java 校验后存 MinIO，再写一条 `kind=image` 消息，content 是 `{"object_key","media_type"}` JSON（`OnlineConsultationMessaging.java:54-70`）：

```java
    /** 图片是问诊消息本体；MinIO 失败必须拒绝发送，不能留下无法回看的空消息。 */
    MessageView sendImage(long consultationId, String senderType, MultipartFile file) {
        Contracts.ConsultationPhotoLimits limits = contracts.consultationPhotoLimits();
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "请选择图片");
        }
        if (file.getSize() > limits.maxBytes()) {
            throw new ApiException(400, "图片不能超过 " + (limits.maxBytes() / 1024 / 1024) + "MB");
        }
        if (!PhotoFileTypes.isAllowedImage(file, limits.allowedTypes())) {
            throw new ApiException(400, "图片仅支持 JPEG/PNG 格式");
        }
        String mediaType = PhotoFileTypes.detectMediaType(file);
        String objectKey = minioStorage.storePhoto(file).orElseThrow(() -> new ApiException(503, "图片发送失败，请稍后重试"));
```

大小/类型上限全部来自 `contracts/consultation-photo-limits.json`（≤2MB、JPEG/PNG、单张）。与拍照分析「MinIO 失败不落图但分析照常」不同，问诊图片失败即发送失败（503），不降级——因为没有可替代的产出物。回看通道双端分离：C 端 `/api/c/photos`，B 端医生经 `/api/b/reception/photos` 代理（`/api/b/photos` 被 AdminInterceptor 限定 admin 角色，医生会 403）；由于 `<img>` 无法携带 Authorization，admin 抽屉用 `AuthPhoto` 组件先 fetch blob 再 `createObjectURL`（`OnlineConsultationDrawer.tsx:414-426`）。业务数据只存 PostgreSQL、图片对象只存 MinIO 的边界在此严格成立，无双写。

### 5.6 医生工作台：待接诊池轮询与抽屉编排

B 端把在线问诊嵌入医生工作台。`OnlineConsultationPanel` 三 Tab（待接诊池/进行中/已完成），池在激活时每 10s 轮询（`OnlineConsultationPanel.tsx:37-43`）：

```tsx
  // 待接诊池：激活时立即加载并每 10s 轮询，切换标签页即清除
  useEffect(() => {
    if (tab !== 'pool') return;
    setPoolLoading(true);
    loadPool().catch(() => {}).finally(() => setPoolLoading(false));
    const timer = setInterval(() => { loadPool().catch(() => {}); }, 10000);
    return () => clearInterval(timer);
  }, [tab, loadPool]);
```

`OnlineConsultationDrawer` 是医生侧全部动作的编排点：打开时按 id 拉详情（含健康档案、过敏史、AI 病情摘要与免责声明），待接诊单点「接受问诊」调 `POST /{id}/accept`——409（accept_conflict）说明单子被抢走，刷新池并关抽屉（`OnlineConsultationDrawer.tsx:201-218`）；进行中每 3s 按 `after_id` 增量拉消息、逐秒倒计时、可发文字/开方/完成。前端有一个细节值得讲：待接诊单**未接受前不拉消息**，因为消息接口对已绑定医生以外的访问统一 404（`requireBoundToDoctor`），提前拉会被全局 errorHandler 弹窗（`OnlineConsultationDrawer.tsx:110-113` 的 `canViewMessages`）。所有状态、文案、消息类型都从 `@/contracts/consultation`（contracts JSON 的 TS 镜像）导入，前端没有硬编码状态串。

## 契约与 ADR

- `contracts/online-consultation.json`：本模块的核心契约——`preconsultation` 场景值、草稿四状态（COLLECTING/PENDING_CONFIRM/SUBMITTED/ABANDONED）、问诊单五状态机（WAITING_DOCTOR/IN_PROGRESS/COMPLETED/CANCELLED/EXPIRED）、跨端五步进度、接诊方式、消息发送者/类型、接诊超时 600s、时长窗 1800s、摘要字段与全部用户文案；`summary_event_field` 小节固化了「摘要异步回调」的协议。
- `contracts/consultation-photo-limits.json`：问诊图片上传限制（≤2MB、JPEG/PNG、单张），server-java 校验与小程序选图约束的事实源。
- `contracts/chat-realtime.json`：WS 信封类型（auth/authenticated/chat/event/error）与端点路径，连接内首帧鉴权的词汇表。
- ADR-0029「在线问诊交流媒体消息：患者图片 + 语音输入，复用 AI 对话模块能力」：消息加 `kind` 列、图片 MinIO 旁路、医生只读回看走 reception 域代理、语音只作输入通道的决策与备选方案。
- ADR-0023「拍照分析图片对象持久化 MinIO」：MinIO 旁路存储的总决策，问诊图片沿用其代理回看通道与孤儿对象策略。
- ADR-0010「跨栈契约：contracts/ JSON 单一事实源 + 双栈启动加载」（注意 docs/adr 下另有一篇同名编号的 ADR-0010「RAG 知识检索只用于受控证据问答与技术演示」，与本模块无直接关系，引用时以全标题区分）：解释为什么小程序 `utils/consultation.js` 与 admin `@/contracts/consultation` 都是契约 JSON 的本地镜像且必须同步维护。

## 讲解提示

- **强调「双侧工作流 + 共享守卫」的分层**：让学生回答「患者发消息要经过几道守卫」——归属（`requireOwnedByPatient`，404 不泄露存在性）→ 惰性收敛（`expireOverdue`）→ 状态（`requireInProgress`）→ 接诊方式（`requireMethodInitiated`），全部在 service 层，controller 零业务逻辑。常见提问「为什么抢单不先查状态再更新」：条件 UPDATE 把谓词下推到数据库，检查与更新是同一原子操作，先查后改在并发下必然有失序窗口。
- **常见提问「摘要为什么不随 message 事件一起下发」**：摘要是一次串行二次 LLM 调用，若阻塞主回复，客户端输入框要等数秒到数十秒才能解锁；改为 done 后后台异步整理 + 回调落草稿 + 端侧 3s/7s/12s 轮询回拉（`preconsult/index.js:215-227`），用「最终一致」换交互流畅，代价是 CTA 亮起有延迟——这是典型的非关键路径降级设计，可对照 emotion judge 的对称语义讲。
- **常见提问「WS 为什么不直接用 Authorization header」**：支付宝 IDE 给自定义 header 值包字面双引号、隧道（cpolar）重建 Upgrade 会剥掉自定义头，两个现实约束叠加使握手期鉴权不可靠；连接内 auth 首帧 + `authenticated` 应答 + 5s 看门狗是规避方案，同时收获「JWT 不进 URL 不进代理日志」的安全收益。
- **安全与合规落点可串联全模块**：红线症状由 server-java 在对话入口确定性判断（先于 LLM）；预问诊 LLM 看不到业务工具（代码级隔离）；科室 ID 双重受控（server-py 归一 + server-java 复检）；摘要与 AI 产出的免责声明一律 server-java 兜底；审计/trace 不记患者敏感原文（回调日志只记草稿 ID 与异常类型）。

> 返回目录：[docs/textbook/README.md](./README.md)
