# 模块9：消息与诊后关怀

## 业务概述

本模块承载诊前/诊后的患者触达闭环：挂号成功即投"就诊指引卡"，处方审核后投审核结果消息并按疗程即时生成服药打卡提醒，患者点"已服用"完成打卡并累计连续天数（streak）。全部逻辑都在 server-java 直写直读，server-py 不参与，是一个"小而完整"的随访闭环——没有后台定时器、没有 LLM，仅靠"事务内写入 + 查询时过滤 + 状态机幂等"三个手段实现。

## 业务流程

1. **挂号投递指引卡**：患者在小程序完成挂号，server-java `AppointmentService.create()` 在同一事务内联查排班→院区静态信息，拼装结构化 JSON，写一条 `type=appointment_care` 的站内消息（失败整体回滚，含 Redis 号源回补）。
2. **消息页聚合展示**：小程序"消息"页 `onShow` 时并行拉取 `/c/messages`（站内消息）与 `/c/med-checkins`（到点服药提醒），指引卡 content 为 JSON，端侧解析后渲染成卡片；已读态仅存本机存储，不改服务端数据。
3. **处方审核触发即时排程**：B 端医生审核处方，`PrescriptionService.review()` 事务内推进状态、写审核结果站内消息；仅当审核通过（APPROVED）时调用 `MedCheckinService.generateForApprovedPrescription()`，按每条明细的 `duration` 展开每日一条 `PENDING` 打卡记录（eager 预生成，写入即结束，无定时任务）。
4. **到点可见**：C 端消息页查询时用 `due_date <= today AND status = PENDING` 过滤，未到提醒日的记录天然不可见——"到点"是查询时算出来的，不是调度器推出来的。
5. **患者打卡**：患者点击"已服用"，`POST /c/med-checkins/{id}/check` 走条件 UPDATE 把 `PENDING` 推进为 `CHECKED`（不可回退），响应中携带现算的 streak，小程序 toast"已打卡，连续 N 天"并把该提醒从列表移除。
6. **进入健康档案时间线**：已打卡记录离开消息通道，经 `HealthProfileMapper` 的时间线联合查询以 `MED_CHECKIN` 分支出现在健康档案时间线，可回看漏服（漏服即 PENDING 长期未推进）。
7. **待办追踪补充**：首页 `PatientConsultationProgressService` 把"已完成但处方未终结"的问诊链路投影为追踪卡（审核中/可购药/未通过），与消息通道互补，引导患者走完诊后链路。

## 代码地图

| 层 | 职责 | 文件路径 |
|---|---|---|
| 小程序页面 | 消息+提醒聚合展示、已读态、打卡交互 | `miniprogram/pages/messages/index.js` |
| 小程序服务 | `/c/messages`、`/c/med-checkins`、`/c/prescriptions` 请求封装 | `miniprogram/services/patient-care.js` |
| server-java controller | C 端消息/处方只读接口 | `server-java/src/main/java/com/zhiyu/health/controller/patient/consultation/PatientCareController.java` |
| server-java controller | C 端服药打卡接口（列表 + check） | `server-java/src/main/java/com/zhiyu/health/controller/patient/prescription/MedCheckinController.java` |
| server-java service | 处方列表与站内消息的患者侧只读投影 | `server-java/src/main/java/com/zhiyu/health/service/consultation/PatientCareService.java` |
| server-java service | 首页问诊待办与诊后处方追踪投影 | `server-java/src/main/java/com/zhiyu/health/service/consultation/PatientConsultationProgressService.java` |
| server-java service | 打卡即时排程（eager 生成）、打卡幂等、streak 现算 | `server-java/src/main/java/com/zhiyu/health/service/prescription/MedCheckinService.java` |
| server-java service | 审核事务内触发打卡预生成 | `server-java/src/main/java/com/zhiyu/health/service/prescription/PrescriptionService.java` |
| server-java service | 挂号事务内写就诊指引卡 | `server-java/src/main/java/com/zhiyu/health/service/appointment/AppointmentService.java` |
| server-java mapper | 打卡记录的幂等 SQL（INSERT ON CONFLICT / 条件 UPDATE / 到点过滤） | `server-java/src/main/java/com/zhiyu/health/mapper/prescription/MedCheckinRecordMapper.java` |
| 契约 | 就诊指引卡消息类型与 content schema | `contracts/appointment-care.json` |
| 契约 | 打卡状态机、文案键、时间线类型 | `contracts/med-checkin-flow.json` |

## 核心代码走读

### 9.1 就诊指引卡：挂号事务内的结构化站内消息

`server-java/src/main/java/com/zhiyu/health/service/appointment/AppointmentService.java:128-154`

```java
private void writeAppointmentCareMessage(long patientId, long scheduleId, long appointmentId) {
    ScheduleMapper.CareContext care = scheduleMapper.selectCareContextBySchedule(scheduleId);
    // 排班刚写入即可联查（外键保证 join 命中），缺失属数据完整性异常；
    // 抛出触发事务回滚（含 Redis 号源回补），符合"失败一起回滚无悬空"硬约束，不留悬空挂号。
    if (care == null) {
        throw new IllegalStateException("就诊指引卡上下文缺失，挂号事务回滚：scheduleId=" + scheduleId);
    }
    String scheduleTime =
            (care.scheduleDate() == null ? "" : care.scheduleDate().toString()) + " " + care.timeSlotValue();
    var content = new java.util.LinkedHashMap<String, Object>();
    content.put("greeting", "挂号成功，请按时就诊");
    content.put("hospital_name", care.hospitalName());
    content.put("department_name", care.departmentName());
    content.put("doctor_name", care.doctorName());
    content.put("schedule_time", scheduleTime.trim());
    content.put("address", care.address());
    content.put("floor", care.floor());
    // materials/precautions 在 seed 中以换行分隔，拆成数组供端侧渲染列表
    content.put("materials", splitLines(care.materials()));
    content.put("precautions", splitLines(care.precautions()));
    String contentJson;
    try {
        contentJson = objectMapper.writeValueAsString(content);
    } catch (JsonProcessingException exception) {
        // content 全为结构化静态值，序列化失败属装配错误，抛出以触发事务回滚暴露问题。
        throw new IllegalStateException("就诊指引卡 content 序列化失败", exception);
    }
```

随后第 155-163 行完成消息装配：`type`/`title` 只取 `contracts.appointmentCare()`，`content` 写入上面的 JSON，`disclaimer` 经 `DisclaimerService` 从契约兜底注入（不信任上游），`relatedAppointmentId` 关联挂号单后 `messageMapper.insert(message)`。

讲解：指引卡的字段集（`greeting`/`hospital_name`/…/`precautions`）由 `contracts/appointment-care.json` 的 `content_schema` 定义，双栈共享；地址、楼层、材料、注意事项全部来自院区表（`hospital_campuses`）的静态 seed，**不经 LLM**。关键点在一致性保障：写消息与挂号在同一事务，上下文缺失或序列化失败直接抛异常回滚（连带 Redis 号源回补），不留"挂号成功但无指引"的中间态；竞态幂等由 `UNIQUE(related_appointment_id, type)` 在数据库层兜底。

### 9.2 C 端消息页：双通道聚合与本地已读态

`miniprogram/pages/messages/index.js:14-35`

```javascript
onShow() {
  ensureLogin()
    .then(() => Promise.all([listMessages(), listMedCheckins()]))
    .then(([messages, reminders]) => {
      const readIds = readMessageIds()
      // 就诊指引卡（票 43）：appointment_care 的 content 是结构化 JSON，解析后挂到 item.care 供卡片渲染。
      const decorated = messages.map((item) => {
        const withReadState = { ...item, isUnread: !readIds.includes(String(item.id)) }
        if (item.type !== 'appointment_care' && item.type !== MESSAGE_TYPES.called) return withReadState
        try {
          const content = JSON.parse(item.content)
          return item.type === MESSAGE_TYPES.called
            ? { ...withReadState, call: content, isCallNotice: true }
            : { ...withReadState, care: content }
        } catch (e) {
          return withReadState
        }
      })
      this.setData({ messages: decorated, reminders })
    })
    .catch(() => my.showToast({ content: '消息加载失败', type: 'fail' }))
    .finally(() => this.setData({ loading: false }))
},
```

讲解：消息页把两条异构通道（`in_app_messages` 一次性事件 + `med_checkin_records` 调度记录）在一次 `onShow` 里聚合成同一页 UI，这正是 ADR-0018"不复用 `in_app_messages` 表，只复用站内消息通道/UI"的端侧落点。注意两个设计取舍：① content JSON 解析失败时降级为纯文本消息而不是报错；② 已读态只写本机 storage（`readInAppMessageIds`），服务端不落已读字段——demo 取舍，视觉上够用、业务数据零侵入。

### 9.3 打卡即时排程：审核通过时的 eager 预生成

`server-java/src/main/java/com/zhiyu/health/service/prescription/MedCheckinService.java:56-70`

```java
public void generateForApprovedPrescription(long prescriptionId) {
    Prescription prescription = prescriptionMapper.selectDetailedById(prescriptionId);
    if (prescription == null) {
        return;
    }
    // 票 56：处方行不带 patient/health_profile，统一经临床上下文按来源（挂号单/在线问诊）
    // 双外键 COALESCE 投影派生，调用方不再各自反查挂号单（在线处方无挂号单可查）。
    ClinicalContextService.ClinicalContext context = clinicalContexts.ofPrescription(prescription);
    long patientId = context.patientId();
    long profileId = context.healthProfileId();
    String pendingStatus = status("pending");
    String disclaimer = disclaimers.text();
    LocalDate start = LocalDate.now(STREAK_ZONE);
    for (PrescriptionItem item : itemMapper.selectDetailed(prescriptionId)) {
        int days = parseDurationDays(item.getDuration());
```

第 71-85 行是内层循环：`offset` 从 0 到 `days-1`，逐日 new 一条 `MedCheckinRecord`（带上 patient/profile/处方明细外键、药品名、用法用量、`dueDate = start.plusDays(offset)`、`status = PENDING`、disclaimer），逐条 `checkinMapper.insertIgnore(record)` 落库。

讲解：这就是"即时排程"的全部——没有 `@Scheduled`、没有消息队列、没有延迟任务。处方审核通过那一刻，把整段疗程按天展开成 `days` 行 `PENDING` 记录同步写库，之后系统再无任何调度动作；"到点提醒"由查询时的 `due_date <= today` 过滤实现（mapper `selectPendingDue`）。幂等下沉到数据库：

`server-java/src/main/java/com/zhiyu/health/mapper/prescription/MedCheckinRecordMapper.java:16-26`

```java
@Update(
        """
        INSERT INTO med_checkin_records
          (patient_id, health_profile_id, prescription_id, prescription_item_id,
           medication_name, dosage, frequency, due_date, status, disclaimer)
        VALUES
          (#{patientId}, #{healthProfileId}, #{prescriptionId}, #{prescriptionItemId},
           #{medicationName}, #{dosage}, #{frequency}, #{dueDate}, #{status}, #{disclaimer})
        ON CONFLICT (prescription_item_id, due_date) DO NOTHING
        """)
int insertIgnore(MedCheckinRecord record);
```

触发点在审核事务内，`server-java/src/main/java/com/zhiyu/health/service/prescription/PrescriptionService.java:184-190`：

```java
Prescription reviewed = prescriptionMapper.selectDetailedById(id);
writeReviewResultMessage(reviewed, reviewTarget);
// 审核通过才 eager 预生成服药打卡提醒（ADR-0017）；驳回不生成。
// 生成幂等由 UNIQUE(prescription_item_id, due_date) 兜底，重复审核静默吞掉。
if (status("approved").equals(reviewTarget)) {
    medCheckinService.generateForApprovedPrescription(id);
}
```

讲解：`duration` 是医生手填的自由文本（如"7天"/"2周"），`parseDurationDays()` 用最小正则 `(\d+)\s*(天|日|周|月)` 解析，抓不到默认 7 天并记日志（`MedCheckinService.java:144-162`）——ADR-0018 明确否决了按次展开（要造"每日3次/睡前"解析器，过度工程）。`ON CONFLICT DO NOTHING` 让重复审核、重投、将来加"重新生成"按钮都无需应用层 select-then-insert。

### 9.4 打卡幂等与归属校验

`server-java/src/main/java/com/zhiyu/health/service/prescription/MedCheckinService.java:102-115`

```java
public MedCheckinView check(long patientId, long recordId) {
    MedCheckinRecord record = checkinMapper.selectOwned(recordId, patientId);
    if (record == null) {
        throw new ApiException(404, "打卡记录不存在");
    }
    int affected = checkinMapper.check(recordId, status("checked"), status("pending"));
    if (affected == 0) {
        // 重复点击：返回当前已打卡状态 + 现算 streak，幂等不报错。
        MedCheckinRecord current = checkinMapper.selectOwned(recordId, patientId);
        return toView(current, streak(patientId, current.getHealthProfileId()));
    }
    MedCheckinRecord checked = checkinMapper.selectOwned(recordId, patientId);
    return toView(checked, streak(patientId, checked.getHealthProfileId()));
}
```

配套 SQL（`server-java/src/main/java/com/zhiyu/health/mapper/prescription/MedCheckinRecordMapper.java:29-31`）：

```java
@Update("UPDATE med_checkin_records SET status = #{checked}, checked_at = now() "
        + "WHERE id = #{id} AND status = #{pending}")
int check(@Param("id") long id, @Param("checked") String checkedStatus, @Param("pending") String pendingStatus);
```

讲解：一句条件 UPDATE 双关"首次打卡"与"重复点击"——`affectedRows=1` 是首次，`=0` 说明已 CHECKED 或不存在，幂等返回当前状态而不报错；`WHERE status = PENDING` 同时保证 CHECKED 不可回退。归属上，`selectOwned` 按 `id + patient_id` 双条件查，越权访问他人记录返回 404 而非 403，避免泄露记录存在性。端侧交互对应 `miniprogram/pages/messages/index.js:51-59`：成功后 toast 展示 streak 并把提醒从列表移除。

### 9.5 streak 现算：不存派生列

`server-java/src/main/java/com/zhiyu/health/service/prescription/MedCheckinService.java:121-142`

```java
public int streak(long patientId, long profileId) {
    List<LocalDate> dates = checkinMapper.selectCheckedDatesDescending(patientId, profileId, status("checked"));
    if (dates.isEmpty()) {
        return 0;
    }
    LocalDate today = LocalDate.now(STREAK_ZONE);
    LocalDate cursor = dates.get(0).equals(today) ? today : today.minusDays(1);
    // 第一个已打日期不是今天也不是昨天，说明已断档，streak=0。
    if (!dates.get(0).equals(cursor)) {
        return 0;
    }
    int streak = 0;
    for (LocalDate due : dates) {
        if (due.equals(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        } else if (due.isBefore(cursor)) {
            break;
        }
    }
    return streak;
}
```

讲解：连续天数不落库，每次打卡后从"今天/昨天"沿已打日期倒序往前数，遇到第一个缺口停（漏一天归零）。ADR-0018 否决了 `current_streak` 派生列方案——增量更新无法表达"漏服归零"，会撒谎（存了 7 但昨天漏了），又得回到要不要定时器的老问题。时区写死 `Asia/Shanghai`（`STREAK_ZONE`，第 37 行），不依赖 JVM/数据库时区漂移。

### 9.6 患者侧只读投影与诊后追踪

`server-java/src/main/java/com/zhiyu/health/service/consultation/PatientCareService.java:25-43`

```java
public List<PatientPrescriptionView> prescriptions(long patientId) {
    // 患者可见性边界（票 60）：全状态处方对患者可见，确定性边界改为「用药解读只随 APPROVED 出现」——
    // interpretation/disclaimer 仅审核通过时落库（ck_prescriptions_patient_visibility 约束不动），
    // 非 APPROVED 天然为 null，本层不再做状态过滤。
    long profileId = healthProfiles.requireActive(patientId).getId();
    return prescriptionMapper.selectForProfile(patientId, profileId).stream()
            .map(this::toPrescriptionView)
            .toList();
}

public List<MessageView> messages(long patientId) {
    return messageMapper.selectForPatient(patientId).stream()
            .map(message -> dtoMapper.toMessageView(
                    message,
                    message.getCreatedAt() == null
                            ? null
                            : message.getCreatedAt().toString()))
            .toList();
}
```

讲解：`PatientCareService` 是典型的"薄投影层"——controller 只做装配，这里只做查询 + MapStruct 映射 + 契约文案（`statusLabel` 来自 `contracts.prescriptionFlow().statusLabels()`），患者可见性边界靠"AI 解读只在 APPROVED 时落库"这一确定性规则保证，而不是在查询层过滤状态。与之互补的 `PatientConsultationProgressService.list()`（`PatientConsultationProgressService.java:32-72`）把预问诊草稿、进行中问诊、"已完成但处方未终结"三类待办投影进首页，`prescriptionTracking()`（第 79-100 行）每档案只取最近一次问诊链路、APPROVED 已下单即交接给药品待支付卡——随访闭环的"提醒"与"待办"两条引导路径在这里汇合。

## 契约与 ADR

- `contracts/appointment-care.json`：就诊指引卡的消息类型（`appointment_care`）与结构化 content schema 的单一事实源，双栈共享。
- `contracts/med-checkin-flow.json`：打卡状态机（`PENDING`/`CHECKED`）、决定（`CHECK`）、消息类型（`MEDICATION_REMINDER`）与时间线类型（`MED_CHECKIN`）。
- `contracts/prescription-flow.json`（间接）：审核结果消息文案、处方状态标签，审核事务内写消息与触发打卡排程的依据。
- `docs/adr/0018-med-checkin-eager-schedule.md`（服药打卡调度模型：eager 预生成 + 到点过滤）：本模块核心决策——无定时器、按天展开、幂等下沉 DB、streak 现算。
- `docs/adr/0010-cross-stack-contracts.md`（注意 docs/adr 下有两个 0010，另一篇是 `0010-rag-knowledge-retrieval.md`）：状态、消息类型等跨栈常量一律从 `contracts/` 加载的依据。
- 提醒：源码注释中多处写"ADR-0017"指代服药打卡（如 `MedCheckinController`、`MedCheckinService`、messages 页），但当前 `docs/adr/0017-agent-call-logs-redaction-and-availability.md` 是 Agent 日志脱敏决策——这是 ADR 重编号后未同步的存量注释，以 `0018-med-checkin-eager-schedule.md` 为准。

## 讲解提示

- 教学强调点：**"即时排程"不等于"定时任务"**。本模块用"写时展开 + 读时过滤"零后台线程实现提醒调度，是 demo 拓扑（本地三服务 + 云数据）下对 `@Scheduled` 轮询、消息队列延迟消息的刻意否决——让学生对比三种方案的重启恢复、幂等、精度成本。
- 教学强调点：**幂等下沉到数据库层**。生成靠 `UNIQUE + ON CONFLICT DO NOTHING`，打卡靠条件 UPDATE 看 `affectedRows`，应用层完全没有 select-then-insert；这是全仓一致风格（挂号、审核同款），适合横向串讲。
- 常见提问："为什么按天不按次展开打卡？"答：`frequency` 是手填自由文本，按次展开要先造时刻解析器并兜底失败，过度工程；按天展开让 streak 定义无歧义（每天至多一次），7 天疗程只产 7 行。
- 常见提问："误打卡能撤回吗？"答：不能——`UPDATE WHERE status=PENDING` 保证 CHECKED 不可回退，换来时间线稳定性；票面未提取消，demo 可接受，真实系统需补"撤销"决定进契约状态机。

> 返回目录：[docs/textbook/README.md](./README.md)
