# 已支付预约取消与模拟退款

Status: accepted（票 90 已支付预约取消与模拟退款）

挂号支付成功后进入待就诊（BOOKED），原设计 C 端只允许待支付（PENDING_PAYMENT）取消，已支付预约无法取消。由于可预约未来日期，已支付但无法取消不符合演示体验。本决策补齐：已支付预约可在号源起始时间前 30 分钟取消，并模拟退款；待支付取消不受时间限制（鼓励尽早释放号源）。

## 决策

1. **退款状态建模**：扩展 `payments` 状态机，新增 `REFUNDED` 态与 `refunded_at` 列。取消 BOOKED 时同事务把 `PAID -> REFUNDED`（CAS 守卫 `WHERE status=PAID`），与现有 `UNPAID/PAID` 同构，可查可审计。`payment-flow.json` 新增 `refunded`/`REFUND` decision 与 `refund_success`/`not_refundable` 文案。
2. **取消时间窗口**：仅对 BOOKED（已支付）态限制"当前时间 ≤ 号源起始时间 - cancel_cutoff_minutes"才可取消；PENDING_PAYMENT（待支付）不受限。`cancel_cutoff_minutes`（默认 30）落在 `contracts/appointment-flow.json`，server-java 直读。
3. **cancellable 后端下发**：遵循 ADR-0034 `callable` 模式--前端不自行复制时段表，由后端 `AppointmentService.toView` 计算 `cancellable` 布尔下发，小程序只渲染。待支付未过期可取消；已支付未过取消截止可取消；其他状态不可取消。
4. **时间窗口复用 EffectiveSlotWindows**：`SlotWindowGuard.isPastCancelCutoff` 复用 `EffectiveSlotWindows`（演示覆盖优先、契约兜底）取时段 start，与挂号截止（`isClosed`）、叫号窗口（`isWithinWindow`）共享同一事实源，避免漂移。非当天返回 false（未来日期可取消）；未知窗口 fail-open 返回 false（不阻断取消，与 `isClosed` 同口径）。
5. **模拟退款**：无真实支付渠道，退款即 `payments.status PAID -> REFUNDED` + 写 `refunded_at`，同步成功。不接外部退款 API，不引入异步退款中间态（REFUNDING）。未来接入真实渠道时可在此扩展。
6. **事务原子性**：取消 BOOKED 时 `markCancelled`（挂号单 CANCELLED）+ `incrementRemainingSlots`（PG 号源回补）+ `refund.grant`（Redis 号源回补）+ `refundIfPaid`（payment PAID->REFUNDED）在同一 `withRefund` 事务内，同提交同回滚。退款 CAS 守卫幂等：重复取消只首次退款（第二次在 CANCELLED 早返回分支不触达 refundIfPaid）。

## 被否决的方案

- **payments 保持 PAID，另起退款记录表/字段**：payments 状态机已能表达退款语义，另起表增加复杂度且与现有 UNPAID/PAID 不对称。扩展状态机最小改动、可查可审计。
- **前端按时段表本地判断可取消**：时段窗口会随演示覆盖变化，前端复制一份会与后端事实源漂移（ADR-0034 已否决同类方案）。改为后端计算 `cancellable` 下发。
- **纯模拟不落库退款痕迹**：无法体现"已退款"事实，列表仍显示"已支付"，体验差且不可审计。
- **引入 REFUNDING 异步中间态**：当前无真实支付渠道，退款同步成功，引入中间态无业务价值。未来接入真实渠道时再评估。

## Consequences

- `payment-flow.json` 状态机从两态扩展为三态（UNPAID/PAID/REFUNDED），admin `payment.ts` 直读 JSON 自动同步，B 端收费列表可见退款状态。
- `AppointmentService.cancel` 对 BOOKED 态增加时间窗口校验与退款调用，PENDING_PAYMENT 路径不变（不调用退款）。
- 超时收敛 `cancelOverdue` 不受影响：它只针对 PENDING_PAYMENT（超时态），不涉及已支付单。
- `SlotWindowGuard` 新增 `isPastCancelCutoff`，时间经注入 `Clock` 读取，测试可固定时钟覆盖取消截止边界。
- C 端取消按钮由 `isCancellable` 驱动（后端下发），已支付预约取消时二次确认文案提示退款，取消成功后列表显示"费用已原路退回"。
