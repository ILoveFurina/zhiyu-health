# 过点就诊中自动转已接诊

Status: accepted（票 94 接诊台增强：过点就诊中自动转已接诊 + 详情补健康档案与处方明细）

医生叫号后患者进入"就诊中"（IN_PROGRESS），但医生忘了点"接诊完成"按钮，过了号源时段（甚至跨天）后该挂号单一直滞留就诊中，卡住单叫号约束（医生无法叫下一个号）。本决策新增惰性收敛：过点（含跨天滞留）的 IN_PROGRESS 单由系统自动推进为 VISITED。

## 决策

1. **惰性收敛，复用 ADR-0033/0038 范式**：新增 `expireUnfinishedConsultations()`，在 `ReceptionService.today()`/`listForPatient`/`cancel` 入口与 `expireOverdueAppointments`/`expireUncalledAppointments` 并排触发。不引入调度中间件，下次入口访问即收敛。
2. **只推进状态，不落接诊记录、不发就诊小结消息、不释放号源、不退款**：医生未填诊断结论与医嘱（`consultation_records.diagnosis/advice` NOT NULL），不伪造医疗内容；患者已就诊，无需退款/释放号源。与 ADR-0038（过点未叫号 BOOKED->CANCELLED+退款）平行，但目标状态是 VISITED（已接诊）。
3. **过点判定复用 SlotWindowGuard 新增 isPast**：跨天滞留（`schedule_date < today`）直接返回 true（不论时段是否已知，彻底清理卡住单叫号约束的滞留单）；当天则复用 `isClosed`（`now > end`，未知时段 fail-open）；未来日期返回 false。
4. **CAS 守卫 + 独立 transition**：契约新增 `auto_complete_overdue`（from=IN_PROGRESS, to=VISITED），区别于医生主动 `complete`（同 from 但落诊断记录+就诊小结）。mapper 新增 `markVisitedIfInProgress` CAS（WHERE status=IN_PROGRESS），SQL 同 `markVisited` 但语义独立，便于审计系统触发 vs 医生主动。CAS 返回 0 跳过（医生已手动完成/已取消）。
5. **处理跨天滞留**：`selectInProgress` 不限当天，查所有 IN_PROGRESS 单。跨天滞留（昨天及以前就诊中未完成）也收敛，避免跨天累积卡住叫号。
6. **前端零改动（状态推进部分）**：VISITED 状态已显示"查看"按钮，自动转后医生看到的就是已接诊（只读）。接诊详情查看时诊断/医嘱为空，属可接受--医生未填诊断，不伪造。

## 被否决的方案

- **落占位 ConsultationRecord（诊断/医嘱填"医生未填写"）+ 发就诊小结消息**：`diagnosis/advice` NOT NULL 但医生未填，占位文案有伪造医疗内容风险，且需调 LLM 生成无意义小结。不伪造医疗内容是硬约束。
- **不自动转，仅靠医生过时段后手动 complete**：ADR-0034 第 4 条已明确完成接诊不拦时段，医生过时段后本就能 complete。但用户痛点是医生忘了点，过时段后卡住单叫号约束，系统应代为收尾。自动转 + 保留医生手动 complete 两条路径并存。
- **只处理当天过点，不处理跨天滞留**：跨天滞留的单更应清理（卡住更久）。`selectInProgress` 不限当天，`isPast` 对跨天直接 true，彻底清理。
- **引入 @Scheduled 定时扫描**：打破项目"不引入调度中间件"硬约束（ADR-0033 明确否决 @Scheduled 与 Redis keyspace notification）。惰性失效已满足"过点后自动处理"语义。

## Consequences

- `AppointmentService` 新增 `expireUnfinishedConsultations`/`autoCompleteOverdue`，`listForPatient`/`cancel`/`ReceptionService.today` 入口并排触发三套收敛（过期待支付 + 过点未叫号 + 过点就诊中）。三者 CAS 守卫互不冲突（from 分别为 PENDING_PAYMENT/BOOKED/IN_PROGRESS）。
- `appointment-flow.json` 新增 `auto_complete_overdue` transition；`Contracts.java` 零改（transition 纯 JSON 加载）。
- `AppointmentMapper` 新增 `selectInProgress`（不限当天）与 `markVisitedIfInProgress` CAS。`SlotWindowGuard` 新增 `isPast`。schema 零变更（VISITED 已存在）。
- 自动转的单查看时诊断/医嘱为空（`detail` 返回 null），前端接诊记录区显示空。若医生需补诊断为后续需求（VISITED 终态不可补）。
- 演示加速：复用 EffectiveSlotWindows 演示覆盖（`/api/b/demo/time-slot-windows`），演示者把窗口 end 调到当前时间后不久，过点即触发收敛。
- 本票同时扩展 `ReceptionService.detail` 返回患者健康档案（性别/年龄/过敏史，取自挂号时固化的 `health_profile_id`）与处方明细（药品列表+状态+驳回原因），供接诊详情与已接诊查看页展示。健康档案信息不取当前活跃档案（患者可能切换档案），接诊台看挂号时锁定的档案。
