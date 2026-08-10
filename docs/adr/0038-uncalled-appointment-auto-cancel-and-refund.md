# 过点未叫号已支付预约的自动取消与退款

Status: accepted（票 92 过点未叫号已支付预约的自动取消与退款）

号源时段窗口 end（上午 11:30 / 下午 18:00）后，已支付（BOOKED）但未被叫号的预约原设计永久滞留待就诊（ADR-0034 第 3 条"过点滞留不引入爽约状态"）。患者挂号单长时间未处理、费用未退，体验差。本决策反转 ADR-0034 第 3 条：过点未叫号的已支付预约由系统惰性收敛为已取消 + 模拟退款 + 站内消息。

## 决策

1. **惰性收敛，复用 ADR-0033 范式**：新增 `expireUncalledAppointments()`，在 `listForPatient`/`cancel`/`ReceptionService.today()` 入口与 `expireOverdueAppointments()` 并排触发。不引入调度中间件（@Scheduled / Redis keyspace notification 仍被否决，与 ADR-0033 同构）；号源释放与退款靠下次入口访问触发。
2. **过点判定复用 SlotWindowGuard.isClosed**：当天且 `now > 时段窗口 end` 即过点。复用 EffectiveSlotWindows（演示覆盖优先、契约兜底）的 end，与挂号截止（isClosed）、叫号窗口（isWithinWindow）、取消截止（isPastCancelCutoff）共享同一事实源。fail-open：未知窗口或 null 不取消（与 isClosed/isPastCancelCutoff 同口径），避免误取消未知时段单。
3. **无 grace，过点即收敛**：不在 end 后加缓冲秒数。CAS 守卫已防误取消已叫号单（markCancelledIfBooked from=BOOKED，IN_PROGRESS 的单返回 0 跳过），grace 无防的对象；惰性失效下"缓冲秒数"也无法精确兑现（非实时倒计时）。
4. **退款复用票 90**：收敛同事务调 `refundIfPaid`（PAID->REFUNDED + refundedAt），与号源回补（markCancelledIfBooked + incrementRemainingSlots + refund.grant）同提交同回滚。退款 CAS 守卫幂等，重复收敛只首次退款。
5. **系统触发 transition**：契约新增 `auto_cancel_uncalled`（from=BOOKED, to=CANCELLED），区别于患者主动 `cancel`（from 含 PENDING_PAYMENT+BOOKED）。mapper 新增 `markCancelledIfBooked` CAS（from=BOOKED only）专用守卫，语义清晰、便于审计。
6. **站内消息**：同事务写一条 `in_app_messages`（type=`appointment_auto_cancelled`，content="医生暂未接诊，费用已原路返回"），`insertIgnoreConflict` + UNIQUE(related_appointment_id, type) 幂等。与待支付超时收敛"不发消息"（ADR-0033）不同--已支付退款需告知患者。

## 被否决的方案

- **引入 @Scheduled 定时扫描**：真正"过点后 N 秒自动变"，但打破项目"不引入调度中间件"硬约束（ADR-0033 明确否决 @Scheduled 与 Redis keyspace notification）。惰性失效已满足"过点后自动处理"语义，患者下次访问即收敛，站内消息保证患者感知。
- **过点后加 grace 缓冲**：CAS 守卫已防误取消已叫号单，grace 无防的对象；惰性失效下 grace 只是阈值偏移（now > end + grace），给不出"grace 秒后一定变"的承诺。用户初拟 10 秒 grace，经讨论后放弃。
- **维持 ADR-0034 第 3 条（过点滞留保持 BOOKED）**：原被否决理由"爽约会改变状态机与表结构，demo 场景无业务价值"不再成立--本方案不改状态机（CANCELLED 已存在）、不改表结构，仅新增系统触发收敛路径，且有明确业务价值（避免挂号单长时间未处理、释放号源、退款告知患者）。
- **C 端列表页加轮询让自动变化可见**：与待支付超时收敛行为不一致（待支付超时也是 onShow 收敛、无轮询）。onShow 收敛 + 站内消息已满足患者感知，轮询是额外改动且破坏一致性。
- **新增爽约状态**：CANCELLED 已能表达"过点未叫号取消"语义，新增爽约状态改变状态机与表结构，无业务价值。复用 CANCELLED + 退款 + 消息即可。

## Consequences

- ADR-0034 第 3 条"过点滞留不引入爽约状态"被反转：过点未叫号的 BOOKED 单不再永久滞留，由系统惰性收敛为 CANCELLED + REFUNDED。ADR-0034 第 3 条原文保留作历史记录，本 ADR-0038 覆盖其"过点滞留"语义。
- `AppointmentService` 新增 `expireUncalledAppointments`/`cancelUncalled`/`writeAutoCancelledMessage`，`listForPatient`/`cancel`/`ReceptionService.today` 入口并排触发两套收敛（待支付超时 + 过点未叫号）。两者 CAS 守卫互不冲突。
- `appointment-flow.json` 新增 `auto_cancel_uncalled` transition 与 `uncalled_notice` 消息配置；`Contracts.java` 新增 `UncalledNotice` record（transition 纯 JSON 加载，Java 零改）。
- `AppointmentMapper` 新增 `selectUncalledBookedToday`（当天 BOOKED 单轻量投影）与 `markCancelledIfBooked` CAS。schema 零变更。
- C 端 + B 端零改动：列表 `isRefunded`（票 90）已展示"费用已原路退回"，消息页未知 type 走纯文本分支，接诊台 `reception_visible_statuses` 不含 CANCELLED 自动过滤。
- 演示加速：复用 EffectiveSlotWindows 演示覆盖（`/api/b/demo/time-slot-windows`），演示者把窗口 end 调到当前时间后不久，过点即触发收敛。
