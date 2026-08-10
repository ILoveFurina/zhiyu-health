# 90 - 已支付预约取消与模拟退款

**What to build:** C 端预约挂号成功支付后当前无法取消，由于可预约未来日期不够人性化。需支持已支付（BOOKED）预约在号源起始时间前 30 分钟取消，并模拟退款（PAID -> REFUNDED）；待支付（PENDING_PAYMENT）取消不受时间限制（鼓励尽早释放号源）。退款与号源回补同事务原子完成，cancellable 标记由后端下发、前端不自行复制时段表（ADR-0034 同构）。ADR-0037 是本票决策依据。

**Blocked by:** 87 - 接诊台叫号时段约束与操作顺序（复用 SlotWindowGuard 与 EffectiveSlotWindows）（已 done）

**Status:** ready-for-agent

## 契约与数据模型

- [x] contracts：`payment-flow.json` 新增 `refunded`/`REFUNDED` 态、`REFUND` decision、`refund_success`/`not_refundable` 文案
- [x] contracts：`appointment-flow.json` 新增 `cancel_cutoff_minutes`（默认 30）及 doc 说明
- [x] Contracts.java：`AppointmentFlow` record 增加 `cancelCutoffMinutes` 字段
- [x] schema：`payments` 表 CHECK 约束扩展为 `IN ('UNPAID', 'PAID', 'REFUNDED')`，幂等 DROP + ADD 保证旧库演进
- [x] schema：`payments` 幂等补建 `refunded_at TIMESTAMPTZ` 列（ADD COLUMN IF NOT EXISTS）

## server-java 业务后端

- [x] SlotWindowGuard：新增 `isPastCancelCutoff(scheduleDate, timeSlotValue, cutoffMinutes)`，复用 EffectiveSlotWindows 取 start，非当天返回 false，当天判断 `now > start - cutoffMinutes`
- [x] Payment 实体：新增 `refundedAt` 字段
- [x] PaymentMapper：新增 `markRefunded` CAS（`WHERE status=PAID` -> `REFUNDED + refunded_at=now()`）
- [x] PaymentService：新增 `refundIfPaid(appointmentId)`，返回是否实际退款；CAS 守卫幂等
- [x] PaymentService：PaymentView 补 `refundedAt` 字段，PaymentDtoMapper 同步映射
- [x] AppointmentService.cancel：BOOKED 态增加时间窗口校验（过截止抛 409"距就诊开始不足半小时，不可取消"）；BOOKED 取消同事务调用 `refundIfPaid`；PENDING_PAYMENT 不退款
- [x] AppointmentView：新增 `cancellable` 字段，`toView` 计算（待支付未过期 / 已支付未过截止）
- [x] AppointmentDtoMapper：`cancellable` 映射；AppointmentController.AppointmentOut 下发 `cancellable`
- [x] AppointmentCardMapper：`toPatientOut` 透传 `cancellable`

## C 端小程序

- [x] appointment.js：`decorateAppointment` 透传后端 `cancellable` 为 `isCancellable`，新增 `isRefunded` 标记
- [x] index.axml：取消按钮 `a:if` 改为 `item.isCancellable`；已取消+已退款显示"费用已原路退回"
- [x] index.js：取消二次确认文案区分待支付/已支付（已支付提示退款），Toast 区分

## 验收与文档

- [x] ContractsTest：payment-flow 断言 refunded/REFUND/refund_success；appointment-flow 断言 cancelCutoffMinutes=30
- [x] AppointmentServiceTest：BOOKED 取消+退款、过截止拒绝、未过截止可取消、待支付不退款、重复取消只退一次
- [x] 受影响 controller 测试（AppointmentControllerTest/AppointmentToolControllerTest）AppointmentView 构造同步
- [x] ADR-0037、README 依赖图 T90 节点、CONTEXT.md 更新
- [ ] schema 完成后运行 `uv run python scripts/reset_zhiyu.py`；重启 server-java 再运行 `uv run python scripts/verify_zhiyu.py`
- [ ] 前端必须支付宝开发者工具实测无控制台错误：支付后预约在半小时前可取消并显示退款、过点不可取消、待支付仍可取消
- [ ] 票单置 done 前：README 依赖图 `T90` 节点加 `[x]`

## Comments

- 退款建模采用扩展 payments 状态机方案（用户确认），新增 REFUNDED 态 + refunded_at 列，与 UNPAID/PAID 同构。
- cancellable 遵循 ADR-0034 callable 后端下发模式，前端不自行复制时段表。
- 模拟退款无真实支付渠道，退款即 PAID->REFUNDED 同步成功，不引入 REFUNDING 异步中间态。
