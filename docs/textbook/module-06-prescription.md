# 模块6：处方 / 购药 / 药房（含用药禁忌）

## 业务概述

本模块覆盖"医生开方 → 药师/管理员审核 → 患者购药 → 订单履约 → 服药打卡"的完整链路：B 端医生在接诊台开具电子处方，开方过程由 server-java 的确定性禁忌规则引擎实时拦截（PG 健康档案过敏史 + Neo4j 禁忌子图，fail closed）；处方审核通过后患者可在 C 端凭处方购药，也可在 AI 对话中直接购买 OTC 药品（ADR-0032）。药房采用平台自营语义（ADR-0026），`medications` 为全局药品目录与库存。AI 在本模块只做两件收窄的事：处方审核通过时生成患者可读的通俗解读（server-py `agent/clinical.py`），以及 C 端通用药品说明书流（`agent/medication.py`）——个性化禁忌判定永远不属于 AI（ADR-0016/0028）。

## 业务流程

1. B 端医生在接诊台（线下挂号或在线问诊）打开开方表单，`GET /api/b/reception/medications` 拉取在售药品目录，可"从模板导入"预填明细。
2. 医生每选一次药品，前端防抖 300ms 调 `POST .../contraindication-check`，server-java 返回 SAFE / BLOCKED / REVIEW_REQUIRED 之一；命中禁忌时按钮禁用并展示原因（体验层拦截）。
3. 提交 `POST /api/b/reception/appointments/{id}/prescriptions`（或在线问诊对应端点）：server-java 从已鉴权挂号单/问诊单派生患者与医生身份，**提交侧强制复跑同一禁忌规则**，命中即 409 拒绝；同一来源只允许一张处方（唯一约束兜底并发）。
4. 处方进入 PENDING，出现在 B 端「处方审核」页。审核人通过/驳回：`POST /api/b/prescriptions/{id}/review`。通过时先在事务外调 server-py 生成处方通俗解读，再在事务内做条件更新（WHERE status=PENDING 防并发双审）、写审核结果站内消息、eager 预生成整段疗程的服药打卡记录（ADR-0018）。驳回必填原因，驳回即终态，不可改方重提（ADR-0030）。
5. 患者收到站内消息后进入 C 端「我的处方」页，仅 APPROVED 处方可下单：调整数量后 `POST /c/drug-orders`，server-java 校验处方归属与状态、按 medication_id 加行锁、条件 UPDATE 预扣库存、写入 UNPAID 订单。
6. OTC 购药无处方：C 端 AI 对话中患者点名药品，server-py 工具 `search_medications` / `prepare_drug_order` 回调 server-java 装配确认卡，确认后同样走 `POST /c/drug-orders`（prescription_id 为空，药品须 is_prescription=FALSE）。
7. 患者在「药品订单」页模拟支付（`POST /c/drug-orders/{id}/pay`，UNPAID → PAID）或取消（库存同事务回补）；B 端药房管理页对 PAID 订单确认完成（→ DONE）。
8. 服药打卡：处方审核通过时已按 duration 展开每日一条 PENDING 提醒；C 端消息页看到到点提醒，点"已服用"条件 UPDATE 推进 CHECKED 并现算连续打卡天数（streak）。

## 代码地图

| 层 | 职责 | 文件路径 |
| --- | --- | --- |
| server-java controller | B 端医生开方、药品目录、实时禁忌检查 | `server-java/src/main/java/com/zhiyu/health/controller/staff/prescription/DoctorPrescriptionController.java` |
| server-java controller | B 端处方审核列表与通过/驳回 | `server-java/src/main/java/com/zhiyu/health/controller/staff/prescription/PrescriptionReviewController.java` |
| server-java controller | B 端处方模板 CRUD | `server-java/src/main/java/com/zhiyu/health/controller/staff/prescription/PrescriptionTemplateController.java` |
| server-java controller | B 端药品（药房）管理 | `server-java/src/main/java/com/zhiyu/health/controller/staff/prescription/MedicationController.java` |
| server-java controller | B 端订单履约（完成/取消） | `server-java/src/main/java/com/zhiyu/health/controller/staff/prescription/DrugOrderAdminController.java` |
| server-java controller | C 端药品订单（下单/取消/支付） | `server-java/src/main/java/com/zhiyu/health/controller/patient/prescription/DrugOrderController.java` |
| server-java controller | C 端服药打卡 | `server-java/src/main/java/com/zhiyu/health/controller/patient/prescription/MedCheckinController.java` |
| server-java controller | server-py 购药工具回调承接（查 OTC/查处方/装配确认卡） | `server-java/src/main/java/com/zhiyu/health/controller/agent/MedicationToolController.java` |
| server-java service | 处方开方/审核主链路，提交侧复跑禁忌规则 | `server-java/src/main/java/com/zhiyu/health/service/prescription/PrescriptionService.java` |
| server-java service | 禁忌检查编排：PG 过敏史 + 已批准药品 + 事实源装配 | `server-java/src/main/java/com/zhiyu/health/service/prescription/ContraindicationService.java` |
| server-java rule | 禁忌确定性判定（过敏匹配/相互作用/fail closed） | `server-java/src/main/java/com/zhiyu/health/rule/ContraindicationRuleEngine.java` |
| server-java rule | Neo4j 禁忌子图只读事实适配器（Cypher） | `server-java/src/main/java/com/zhiyu/health/rule/Neo4jContraindicationFactRepository.java` |
| server-java service | 订单状态机：处方/OTC 双路径、库存预扣与回补 | `server-java/src/main/java/com/zhiyu/health/service/prescription/DrugOrderService.java` |
| server-java service | 服药打卡：eager 预生成、幂等打卡、streak 现算 | `server-java/src/main/java/com/zhiyu/health/service/prescription/MedCheckinService.java` |
| server-java service | 药房药品只读+编辑（不增删，保护禁忌子图对齐） | `server-java/src/main/java/com/zhiyu/health/service/prescription/MedicationAdminService.java` |
| server-java service | 处方模板（按 doctor_id 归属） | `server-java/src/main/java/com/zhiyu/health/service/prescription/PrescriptionTemplateService.java` |
| server-java service | AI 购药回调的只读数据装配 | `server-java/src/main/java/com/zhiyu/health/service/MedicationToolService.java` |
| server-java agentclient | 调 server-py 生成处方解读（非流式） | `server-java/src/main/java/com/zhiyu/health/agentclient/ClinicalAgentApi.java` |
| server-py agent | 处方通俗解读 / 就诊小结生成 | `server-py/app/agent/clinical.py` |
| server-py agent | 通用药品说明书流（系统提示词约束边界） | `server-py/app/agent/medication.py` |
| server-py api | 药品说明书 SSE 入口（token×N → done） | `server-py/app/api/medication.py` |
| server-py tools | AI 购药 @tool 定义点 | `server-py/app/tools/business.py` |
| server-py tools | 已鉴权回调 server-java 的 HTTP 通道 | `server-py/app/tools/callback.py` |
| B 端页面 | 开方表单：防抖禁忌检查 + 命中禁用提交 | `admin/src/pages/Workbench/components/PrescriptionForm.tsx` |
| B 端页面 | 处方审核列表与通过/驳回 | `admin/src/pages/Prescription/index.tsx` |
| B 端页面 | 药品管理与库存抽屉 | `admin/src/pages/Medication/index.tsx`、`components/PharmacyStockDrawer.tsx` |
| B 端页面 | 订单履约管理 | `admin/src/pages/DrugOrder/index.tsx` |
| C 端页面 | 我的处方：状态分支、处方药下单 | `miniprogram/pages/prescriptions/index.js` |
| C 端页面/服务 | 药品订单列表、取消、模拟支付 | `miniprogram/pages/drug-orders/index.js`、`miniprogram/services/drug-orders.js` |
| C 端工具 | 处方来源/状态枚举的端侧镜像（对齐契约） | `miniprogram/utils/prescription.js` |
| 契约 | 处方状态机、审核决定、来源类型、站内消息文案 | `contracts/prescription-flow.json` |
| 契约 | 禁忌规则决定、卡片类型与固定话术 | `contracts/contraindication.json` |
| 契约 | 订单状态机与来源（PRESCRIPTION/OTC） | `contracts/order-flow.json` |
| 契约 | 服药打卡状态机 | `contracts/med-checkin-flow.json` |
| 契约 | 通用药品说明书流事件与话术 | `contracts/medication-knowledge.json` |

## 核心代码走读

### 6.1 禁忌规则引擎走读：事实装配 → 规则判断

禁忌检查分三段：编排（`ContraindicationService`）→ 事实装配（`Neo4jContraindicationFactRepository`）→ 纯规则判断（`ContraindicationRuleEngine`）。先看编排，`server-java/src/main/java/com/zhiyu/health/service/prescription/ContraindicationService.java:52-64`：

```java
List<String> allergies = allergyMapper.selectAllergens(profile.getId());
List<Long> approvedMedicationIds = prescriptionItemMapper.selectMedicationIdsByHealthProfileAndStatus(
        profile.getId(), contracts.prescriptionFlow().statuses().get("approved"));
LinkedHashSet<Long> checkedMedicationIds = new LinkedHashSet<>(medicationIds);
checkedMedicationIds.addAll(approvedMedicationIds);
ContraindicationFacts facts;
try {
    facts = factRepository.load(List.copyOf(checkedMedicationIds));
} catch (RuntimeException unavailable) {
    // 医学事实源不可用时必须 fail closed：不猜测安全，也不把异常细节或患者数据写入日志。
    facts = new ContraindicationFacts(List.of(), List.of(), false);
}
return ruleEngine.judge(allergies, medicationIds, facts);
```

注意两点：可信上下文与医学事实分库——过敏史、已批准在用的药品来自 PostgreSQL（业务事实），药品成分/禁忌/相互作用只来自 Neo4j（医学知识，硬约束 3）；检查范围是"候选药品 ∪ 患者已批准在用的药品"，因为相互作用可能发生在旧药与新药之间。Neo4j 不可用时**不猜测安全**，直接装配 `complete=false` 的空事实交给规则引擎 fail closed。

事实装配在 `Neo4jContraindicationFactRepository.java:19-40` 用两条固定 Cypher 完成（READ session，不承担判断）：

```java
private static final String MEDICATION_FACTS =
        """
        UNWIND $medicationIds AS medicationId
        OPTIONAL MATCH (m:Medication {medication_id: medicationId})
        OPTIONAL MATCH (m)-[:CONTRAINDICATED_FOR]->(c:Contraindication)
        RETURN medicationId, m IS NOT NULL AS found,
               coalesce(m.ingredients, []) AS ingredients,
               [allergen IN collect(DISTINCT c.allergen) WHERE allergen IS NOT NULL] AS allergyTerms
        ORDER BY medicationId
        """;
```

规则判断在 `ContraindicationRuleEngine.java:24-26` 与 `:36-41`：

```java
if (!facts.complete() || !factMedicationIds.containsAll(candidateMedicationIds)) {
    return result(contract, "review_required", true, List.of("禁忌知识数据不完整，无法确认候选药品安全性"));
}
...
for (String allergy : allergies) {
    if (medicationTerms.stream().anyMatch(term -> matches(allergy, term))) {
        reasons.add("过敏史“%s”与药品 %d 的成分/禁忌项匹配".formatted(allergy.trim(), medication.medicationId()));
    }
}
```

判定顺序：①数据不完整（药品在图中缺失或缺成分字段）→ REVIEW_REQUIRED 且 blocked=true（fail closed 与命中禁忌同等待遇）；②患者过敏史与药品成分/禁忌词做归一化包含匹配（`matches` 去空格转小写后双向 contains）；③候选集内部及与在用药之间的 `INTERACTS_WITH` 相互作用。任一命中 → BLOCKED。决定值与话术全部取自 `contracts/contraindication.json`，规则引擎不产生自由文本；类注释明确"LLM 不参与也不能覆盖判定"。

### 6.2 开方链路：前端防抖拦截是体验层，提交侧强制复跑才是安全边界

B 端开方表单在选药变化后防抖调用实时检查（`admin/src/pages/Workbench/components/PrescriptionForm.tsx:85-92`），命中即禁用提交按钮（`:143`）：

```tsx
const timer = setTimeout(() => {
  runCheck(medicationIds)
    .then((result) => { if (!stale) setSafety(result); })
    .catch(() => { if (!stale) setSafety(undefined); })
    .finally(() => { if (!stale) setChecking(false); });
}, 300);
return () => { stale = true; clearTimeout(timer); };
```

但禁用按钮永远不是安全边界。`PrescriptionService.persist()`（`server-java/src/main/java/com/zhiyu/health/service/prescription/PrescriptionService.java:97-103`）在落库前用同一规则再判一次：

```java
// 提交侧强制复跑同一确定性规则：前端禁用按钮只是体验层，不能作为安全边界。
ContraindicationResult safety = contraindicationService.check(new ContraindicationService.CheckCommand(
        context.patientId(),
        items.stream().map(CreateItem::medicationId).toList()));
if (safety.blocked()) {
    throw safetyException(safety);
}
```

同时注意身份模型：患者/医生身份不接受请求体传入，全部由 `ClinicalContextService` 从已鉴权医生名下的挂号单/在线问诊单派生（`PrescriptionService.java:53-58` 注释："患者身份只来自已鉴权医生名下的挂号单，绝不接受请求体传入"）。绕过前端直接 POST 的攻击者既无法伪造患者身份，也绕不过提交侧的确定性复跑。

### 6.3 处方审核：事务外生成 AI 解读，事务内条件更新 + 站内消息 + 打卡预生成

`PrescriptionService.review()`（`PrescriptionService.java:149-192`）是跨栈协作的典型编排：

```java
if (decision("approve").equals(decision)) {
    List<PrescriptionItem> items = itemMapper.selectDetailed(id);
    AgentClient.ClinicalResponse generated = agentClient.explainPrescription(
            items.stream().map(this::toAgentFact).toList());
    target = status("approved");
    interpretation = generated.content();
    // 患者可见出口使用 Java 侧统一契约，模型字段仅作传输兼容。
    disclaimer = disclaimers.text();
}
...
if (prescriptionMapper.review(
                id, reviewTarget, trimmedReason, reviewerId,
                reviewInterpretation, reviewDisclaimer, status("pending"))
        != 1) {
    throw new ApiException(409, "电子处方已审核");
}
```

设计要点：

- AI 解读生成是 HTTP 调用（`ClinicalAgentApi.java:18-20`，POST `/api/agent/clinical/prescription-explanation`），**刻意保持在数据库事务外**；事务内只做状态推进、写审核结果站内消息、调用 `medCheckinService.generateForApprovedPrescription(id)` eager 预生成打卡（ADR-0018），任一失败整体回滚，不留"已审核但患者无感知"的中间态。
- 并发审核由条件更新 `WHERE status = PENDING` 兜底：两个审核人同时操作只有一个 affectedRows=1，另一个 409。
- 免责声明不信任上游返回，`disclaimers.text()` 在 server-java 出口统一注入（硬约束 1）。驳回即终态，同一来源不可再开新方（ADR-0030，唯一约束 + `PrescriptionService.java:71-73` 的 409 预检）。

### 6.4 购药订单：处方/OTC 双路径与库存条件扣减

`DrugOrderService.createInTransaction()`（`server-java/src/main/java/com/zhiyu/health/service/prescription/DrugOrderService.java:88-118`）按 `prescriptionId` 是否为空分双路径（ADR-0032）：

```java
if (command.prescriptionId() != null) {
    Prescription prescription =
            prescriptionMapper.selectForPatient(command.prescriptionId(), command.patientId());
    String approved = contracts.prescriptionFlow().statuses().get("approved");
    if (prescription == null) {
        throw new ApiException(404, "电子处方不存在");
    }
    if (!approved.equals(prescription.getStatus())) {
        throw new ApiException(409, "仅已审核通过的电子处方可购药");
    }
    // 统一按 medication_id 加行锁，既固定并发锁顺序防死锁，也保证本单采用的价格快照稳定。
    medications = medicationMapper.selectForPrescriptionForUpdate(command.prescriptionId());
```

库存预扣与回补都是条件 UPDATE，不先查后改（`:120-125`、`:250-254`）：

```java
// 库存只能由带 stock >= n 条件的 UPDATE 预扣；任一药品不足即抛错，PG 事务回滚此前扣减。
for (Line line : lines) {
    if (medicationMapper.deductStock(line.medication().getId(), line.quantity()) == 0) {
        throw new ApiException(409, contracts.orderFlow().messages().get("stock_insufficient"));
    }
}
```

OTC 路径的硬约束在 `linesForOtc()`（`:181-184`）：`is_prescription=TRUE` 的药品走 OTC 下单直接 409"处方药须凭已审核电子处方购买"。取消订单时先锁订单行再逐条 `restoreStock`，库存回补与状态更新同事务提交，C/B 两端取消入口共用 `cancelLocked()` 避免重复回补。状态推进（pay/complete/cancel）全部走 `WHERE status = <前置状态>` 的条件更新，affectedRows=0 即 409。

### 6.5 工具调用：AI 购药链路（@tool 定义 → 注册 → 鉴权回调 → server-java 承接）

AI 购药（票 76-78）的三个工具定义点在 `server-py/app/tools/business.py`，以 `@tool` 装饰（`:108-152`）：

```python
@tool
async def search_medications(name: str) -> dict[str, Any] | str:
    """按药名模糊查询在售非处方药（OTC），供用户点名买药时查药；只返回可直接下单的药品。"""
    return await forward_get(
        client,
        "/api/agent/medications",
        {"name": name.strip()},
        action="查询药品",
    )

@tool
async def prepare_drug_order(
    runtime: ToolRuntime[AgentContext],
    medication_id: int | None = None,
    quantity: int | None = None,
    prescription_id: int | None = None,
```

- **工具定义点**：`search_medications`（business.py:108）、`list_approved_prescriptions`（:118）、`prepare_drug_order`（:130）。工具只读——`prepare_drug_order` 装配确认卡（实时单价/库存/总价测算），**不扣库存不建订单**，真正下单仍是端侧 `POST /c/drug-orders`；`patient_id` 从 `runtime.context`（可信上下文）取，不由模型传入。
- **注册进 LangGraph**：`build_business_tools()` 在 `server-py/app/bootstrap.py:42` 被调用（`[*build_business_tools(business_client), *build_department_tools(directory)]`），拼装的工具列表传入 `AgentRunner`；`server-py/app/agent/runner.py:124` 存为 `_base_tools`，`:139-153` 按场景/知识源经 `_tools_for()` 选择后以 `tools=` 传给 `create_agent`。
- **鉴权回调**：`server-py/app/tools/callback.py:22-28` 的 `BusinessCallbackClient` 用 httpx 直连 server-java，携带 `X-Agent-Callback-Token` 头（共享密钥）；`forward_get`（:61-71）把网络失败/业务拒绝降级为可读文本，避免异常穿透 LangGraph 掐断 SSE。
- **server-java 承接**：`controller/agent/MedicationToolController.java:33-56`，`GET /api/agent/medications`（查 OTC）、`GET /api/agent/prescriptions`（查患者已审核处方）、`GET /api/agent/drug-orders/prepare`（装配确认卡），三个端点均经 `AgentCallbackAuthFilter` 鉴权，业务逻辑委托 `MedicationToolService`。

### 6.6 AI 边界：通用药品知识流与处方解读，永远不做个性化判定

C 端"问药/拍药盒"收口为通用说明书流（ADR-0028 部分取代 ADR-0025）。边界写死在系统提示词里（`server-py/app/agent/medication.py:31-34`）：

```python
"不得做任何个性化判断：你不知道也不许询问患者的年龄、病史、过敏史或正在使用的药品；"
"不得给出针对个人的剂量调整，不得推荐替代药品，不得做用药安全判定。\n"
"用法用量只讲说明书级别的通用信息，并提示具体遵医嘱。\n"
f"如果你不了解该药品或药名明显有误，只输出：{unknown_drug}"
```

这条链路不绑任何业务表、不回调 server-java、不使用工具——`ChatMedicationKnowledgeStreamer` 直接把"药名（不可信输入）"发给模型流式输出。SSE 出口在 `server-py/app/api/medication.py:27-31` 注入契约免责声明：

```python
async for token in streamer.stream(drug_name):
    yield sse_frame(token_event, {"text": token})
# 硬约束 1：结尾注入契约免责声明（server-py 生成时注入，server-java 出口兜底）
yield sse_frame(token_event, {"text": "\n\n" + get_contracts().disclaimer.text})
yield sse_frame(done_event, {})
```

B 端处方解读同理收窄（`server-py/app/agent/clinical.py:23-28`）：只能解释输入中的药品/规格/剂量/频次/疗程/备注，"不得新增药品、改变医嘱或作出诊断"，输入仅来自医生已确认的结构化处方明细。对照硬约束 2：个性化禁忌判定只在 B 端开方链路由 server-java 确定性执行（6.1/6.2），AI 侧完全没有这个能力——这是产品刻意砍掉的能力（ADR-0016），不是未实现。

## 契约与 ADR

- `contracts/prescription-flow.json`：处方状态机（PENDING/APPROVED/REJECTED）、审核决定、来源类型（APPOINTMENT/ONLINE_CONSULTATION，仅派生展示不落库）、审核结果站内消息文案。
- `contracts/contraindication.json`：禁忌规则三分支决定（SAFE/BLOCKED/REVIEW_REQUIRED）、卡片消息类型与全部固定话术——两端只消费契约值，LLM 不得改写。
- `contracts/order-flow.json`：药品订单状态机（UNPAID/PAID/DONE/CANCELLED）与来源枚举（PRESCRIPTION/OTC），附 drug_order 卡片 content 字段清单。
- `contracts/med-checkin-flow.json`：服药打卡状态机（PENDING/CHECKED）与提醒/时间线类型。
- `contracts/medication-knowledge.json`：通用说明书流的 SSE 事件（token/done）与流尾话术、未识别药名话术。
- ADR-0016《C 端 Agent 不做个性化用药决策》：C 端移除 check_contraindication 工具，禁忌检查仅留 B 端开方流程。
- ADR-0025《拍药盒架构：视觉只提药名 + 工具回调 + 规则引擎 + 双出口》：早期跨栈设计，决策 1/2/3 已被 ADR-0028 废除，vision 纯 OCR 提名仍有效。
- ADR-0026《药品履约：平台自营药房，不做处方外流与多药店》：`medications` 是全局目录与库存的领域语义。
- ADR-0030《处方驳回即终态，不开放改方重提》：REJECTED 后患者被引导重新问诊/挂号，唯一约束不动摇。
- ADR-0032《OTC 药品无处方直接下单，处方药凭已审核处方下单》：`drug_orders.prescription_id` 可空，双路径由此而来。
- 延伸：ADR-0018《服药打卡调度模型：eager 预生成 + 到点过滤》、ADR-0028《C 端药品说明收口为通用知识流，个性化禁忌仅留 B 端开方》。

## 讲解提示

- **教学强调点：fail closed 的完整实现链。** 让学生追一条"Neo4j 挂了会发生什么"的路径：`ContraindicationService` 捕获异常装配 `complete=false` → 规则引擎判 REVIEW_REQUIRED 且 blocked=true → 提交侧 409 拒绝 → 前端按钮禁用。安全系统宁可误拒不可误放，这是与"尽量可用"的业务功能完全相反的设计取向。
- **常见提问：为什么前端都禁用按钮了，后端还要再查一遍？** 答案要点：HTTP 请求可绕过前端直接构造；且开方表单展示时与提交时之间，健康档案/药品数据可能已变化（TOCTOU）。前端检查是体验优化（即时反馈），提交侧复跑才是安全边界——且两次跑的是同一个 `ContraindicationRuleEngine`，判定不可能分叉。
- **常见提问：为什么相互作用检查要把患者已在用的药品也拉进 Neo4j 查询？** 答案要点：相互作用发生在"新药 × 在用药"之间，只查候选药品集会漏掉这类冲突；`checkedMedicationIds = 候选 ∪ 已批准在用药` 的设计保证了检查域完整。
- **常见提问：处方审核为什么还要调 AI？AI 出错怎么办？** 答案要点：审核本身（状态机推进）是确定性的，AI 只生成患者可读的通俗解读文案；生成失败抛 502 整个审核不生效（事务未开始），可安全重试；解读随事务落库，患者看到的是审核那一刻的快照，且免责声明由 server-java 出口注入，不信任模型输出。

> 返回目录：[docs/textbook/README.md](./README.md)
