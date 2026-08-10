# 92 - 过点未叫号已支付预约的自动取消与退款

**What to build:** 号源时段窗口结束（上午 11:30 / 下午 18:00）后，已支付（BOOKED）但未被叫号的预约，由系统惰性收敛为已取消 + 模拟退款（PAID->REFUNDED）+ 发站内消息「医生暂未接诊，费用已原路返回」。反转 ADR-0034 第 3 条「过点滞留不引入爽约状态」。复用票 90 退款事务与 ADR-0033 惰性失效范式，不引入调度中间件。

**Blocked by:** 90 - 已支付预约取消与模拟退款（复用 refundIfPaid、isRefunded、withRefund 事务结构）（已 done）

**Status:** ready-for-agent

## 决策（用户确认）

- 触发方式：惰性失效，复用 `expireOverdueAppointments` 同款机制，不引入调度中间件（ADR-0033）
- C 端可见性：onShow 收敛，不加轮询（与待支付超时一致）
- 判定：`now > 时段窗口 end`，**无 grace**（过点即收敛）
- 过点判定复用 `SlotWindowGuard.isClosed`（当天且 `now > end`，fail-open 未知窗口不取消），无需新增窗口方法
- 反转 ADR-0034 第 3 条，新增 ADR-0038 记录

## 契约与数据模型

- [x] contracts/appointment-flow.json：新增 `auto_cancel_uncalled` transition（`from=["BOOKED"]`, `to="CANCELLED"`）
- [x] contracts/appointment-flow.json：新增 doc 说明「过点未叫号惰性收敛为已取消+退款+消息，反转 ADR-0034 第 3 条；复用 EffectiveSlotWindows 的 end 作过点判定」
- [x] contracts/appointment-flow.json：新增消息 type `appointment_auto_cancelled`（title/content/disclaimer 文案，参考 `called_notice` 结构）
- [x] Contracts.java：`AppointmentFlow` 新增 `UncalledNotice` record 与字段（transition 纯 JSON 加载，Java 零改）
- [x] schema：零变更（CANCELLED/REFUNDED 状态、in_app_messages 表、`UNIQUE(related_appointment_id, type)` 均已就绪）

## server-java 业务后端

- [x] AppointmentMapper：新增 `selectUncalledBookedToday(bookedStatus)` 查询今天 BOOKED 单（JOIN schedules 取 schedule_date + time_slot），轻量投影 `UncalledAppointment(id, scheduleId, patientId, scheduleDate, timeSlot)`
- [x] AppointmentMapper：新增 `markCancelledIfBooked(appointmentId, bookedStatus, cancelledStatus)` CAS（`WHERE status = booked`），与患者主动 `markCancelled`（from 含两态）区分，语义清晰
- [x] AppointmentService：新增 `expireUncalledAppointments()`，查今天 BOOKED 单，逐个 `slotWindowGuard.isClosed(scheduleDate, timeSlotValue)` 过滤，命中调 `cancelUncalled`；单条失败不阻断其余（与 `expireOverdueAppointments` 同构）
- [x] AppointmentService：新增 `cancelUncalled(appointmentId, scheduleId, patientId)` 私有方法，`withRefund` 事务内：`markCancelledIfBooked`（CAS 返回 0 跳过）+ `incrementRemainingSlots` + `refundIfPaid` + 写站内消息（`insertIgnoreConflict`）+ `refund.grant`
- [x] AppointmentService：在 `listForPatient`、`cancel` 入口紧邻 `expireOverdueAppointments()` 调 `expireUncalledAppointments()`
- [x] ReceptionService.today()：第 51 行 `expireOverdueAppointments()` 旁加 `expireUncalledAppointments()`（接诊台入口也收敛，过点单从队列消失）
- [x] 站内消息：type=`appointment_auto_cancelled`，title="挂号已自动取消"，content="医生暂未接诊，费用已原路返回"，disclaimer=通用免责，`related_appointment_id` 幂等

## C 端小程序 + B 端

- [x] C 端零改动：列表 `isRefunded`（appointment.js:34）已展示「费用已原路退回」（票90 axml）；消息页未知 type 走纯文本分支（index.axml a:else）。仅人工实测确认
- [x] B 端零改动：`reception_visible_statuses=[BOOKED, IN_PROGRESS, VISITED]` 不含 CANCELLED，收敛后接诊台队列自动不显示该单；payment-flow.json REFUNDED 态票90 已做、admin 直读 JSON 自动同步。仅人工实测确认

## 验收与文档

- [x] ContractsTest：appointment-flow 断言 `auto_cancel_uncalled` transition（from=["BOOKED"], to="CANCELLED"）+ 消息 type 存在
- [x] AppointmentServiceTest：过点未叫号 BOOKED 收敛+退款+消息；未过点不收敛；IN_PROGRESS 不收敛（CAS 跳过）；未知窗口不收敛（fail-open）；重复收敛幂等（CAS + 消息 UNIQUE）
- [x] 受影响 controller 测试（AppointmentControllerTest / ReceptionControllerTest / AppointmentToolControllerTest）均 mock service，视图构造无需同步
- [x] ADR-0038（反转 ADR-0034 第 3 条，记录惰性收敛+退款+消息决策与被否决的 grace/定时方案）+ 修订 CONTEXT.md「叫号」「挂号单」「挂号收费」词条 + README 依赖图 T91 节点
- [x] server-java 单元测试 + spotless:check 通过（64 tests, 0 failures）
- [ ] 前端实测：演示时段覆盖（`/api/b/demo/time-slot-windows`）加速，过点后进列表见已取消+退款、消息页有「医生暂未接诊，费用已原路返回」
- [ ] 票单置 done 前：README 依赖图 T91 节点加 `[x]`

## Comments

- 反转 ADR-0034 第 3 条「过点滞留不引入爽约状态」：过点未叫号的已支付预约不再永久滞留 BOOKED，由系统惰性收敛为已取消+退款+消息。ADR-0034 第 3 条原被否决理由「爽约会改变状态机与表结构，demo 场景无业务价值」不再成立--本方案不改状态机（CANCELLED 已存在）、不改表结构，仅新增系统触发收敛路径，且有明确业务价值（避免挂号单长时间未处理）。
- 复用 `SlotWindowGuard.isClosed` 作过点判定（当天且 `now > end`，fail-open 未知窗口不取消，与 `isPastCancelCutoff` 同口径）。
- 复用票 90 退款事务结构与 `refundIfPaid`；系统触发路径无患者身份校验（与 `cancelOverdue` 同构），但需从 appointment 取 patient_id 写消息。
- 惰性失效，不引入调度中间件（ADR-0033）；onShow 收敛，不加轮询（与待支付超时一致）。
- 无 grace，过点即收敛（用户最终确认）：CAS 守卫已防误取消已叫号单（IN_PROGRESS 不在收敛范围），grace 无防的对象。
- 与待支付超时收敛的关系：`expireOverdueAppointments` 先收敛待支付超时单，`expireUncalledAppointments` 再收敛过点 BOOKED 单；两者入口并排触发，互不冲突（CAS 守卫）。
