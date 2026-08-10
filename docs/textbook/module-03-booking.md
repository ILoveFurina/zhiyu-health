# 模块3：挂号 / 预约 / 支付

## 业务概述

本模块实现 C 端线下挂号全流程：患者在支付宝小程序内经"医院 → 院区 → 科室 → 医生 → 排班 → 确认"六步向导选定号源并下单，挂号成功即进入待支付（PENDING_PAYMENT）并占用号源，支付完成才推进为待就诊（BOOKED）并出现在 B 端接诊台。支付为模拟状态机（ADR-0012：业务实体真实、支付动作 Mock），C 端与 B 端收费管理页共用同一支付入口语义。并发正确性由"Redis 原子 DECR + PostgreSQL 事务对账 + 失败补偿"保证（ADR-0007/0011），禁止先查后改。

## 业务流程

1. C 端小程序进入挂号向导：`pages/booking/hospitals` 按当前城市（硬筛选边界）列出医院 → `campuses` 选院区 → `departments` 选院区实际科室 → `doctors` 选医生（含挂号费透传）。
2. `schedules` 页展示该医生今天起 14 天排班（`GET /c/doctors/{id}/schedules`），患者选中日期与上午/下午时段，剩余号源为 0 的时段置灰不可点。
3. `confirm` 页汇总排班信息，点击确认后调 `POST /c/appointments`（`services/directory.js` 的 `createAppointment`）。
4. server-java `AppointmentService.reserve()` 在 Redis 预扣保护下开启 PG 事务：排班行锁 → 时段截止/停诊审核冻结校验 → 幂等查重 → Redis DECR 预扣 → PG 对账扣减 → 写入挂号单（状态 PENDING_PAYMENT，附 60 秒支付截止）→ 同事务写"就诊指引卡"站内消息。
5. 挂号事务提交后，`PaymentService.createUnpaid()` 补建 UNPAID 收费记录（附属记录失败不撤销真实挂号，幂等请求会重试补建）。
6. 患者在"我的挂号"页看到待支付单与倒计时；点击"模拟支付"调 `POST /c/appointments/{id}/payment/pay`，server-java 在收费行锁内把 payment 置 PAID，并 CAS 推进挂号单 PENDING_PAYMENT → BOOKED。
7. B 端 admin「收费管理」页可查看全部收费记录，并对 UNPAID 记录执行"模拟支付"（与 C 端复用同一锁后状态机，先到先付）。
8. 待支付单超过 `payment_timeout_seconds`（演示 60 秒）未支付：在 C 端列表/支付/取消入口惰性收敛为 CANCELLED 并释放号源（不引入调度中间件，ADR-0033）；患者手动取消同样走"PG 回补 + Redis 退还"。
9. 挂号单进入 BOOKED 后对接诊台可见（白名单 `BOOKED/IN_PROGRESS/VISITED`），后续叫号、接诊流转见模块：接诊台章节。

## 代码地图

| 层 | 职责 | 文件路径 |
| --- | --- | --- |
| C 端页面 | 六步向导：医院/院区/科室/医生/排班/确认 | `miniprogram/pages/booking/{hospitals,campuses,departments,doctors,schedules,confirm}/index.js` |
| C 端页面 | "我的挂号"列表、倒计时、取消与支付 | `miniprogram/pages/appointments/index.js` |
| C 端服务 | 目录与号源查询、挂号下单 API 封装 | `miniprogram/services/directory.js` |
| C 端服务 | 挂号单列表/取消/支付 API 封装 | `miniprogram/services/appointments.js` |
| C 端服务 | AI 挂号助手主卡数据装配（首页/对话空态共用） | `miniprogram/services/registration.js` |
| server-java controller | C 端"我的挂号"（列表/直挂号/取消），只做患者身份装配 | `server-java/src/main/java/com/zhiyu/health/controller/patient/appointment/AppointmentController.java` |
| server-java controller | C 端挂号收费支付入口，入口前置惰性收敛 | `server-java/src/main/java/com/zhiyu/health/controller/patient/appointment/AppointmentPaymentController.java` |
| server-java controller | B 端收费管理（列表/明细/模拟支付） | `server-java/src/main/java/com/zhiyu/health/controller/staff/appointment/PaymentController.java` |
| server-java service | 挂号核心：预扣/对账/写入/取消/超时收敛 | `server-java/src/main/java/com/zhiyu/health/service/appointment/AppointmentService.java` |
| server-java service | 收费记录与支付状态机（B/C 端共用） | `server-java/src/main/java/com/zhiyu/health/service/appointment/PaymentService.java` |
| server-java service | 号源记账收口：Redis+PG 双写一致性唯一入口 | `server-java/src/main/java/com/zhiyu/health/service/scheduling/SlotAccounting.java` |
| server-java service | Redis 号源计数器（DECR/INCR/INCRBY） | `server-java/src/main/java/com/zhiyu/health/service/scheduling/RedisSlotCounter.java` |
| server-java service | 号源计数 Redis 键格式单一事实源 | `server-java/src/main/java/com/zhiyu/health/service/scheduling/SlotKeys.java` |
| server-java mapper | 排班余量对账 SQL（条件 UPDATE，不先查后改） | `server-java/src/main/java/com/zhiyu/health/mapper/scheduling/ScheduleMapper.java` |
| B 端页面 | 收费管理列表、状态筛选、模拟支付 | `admin/src/pages/Payment/index.tsx` |
| B 端服务 | `/api/b/payments` API 封装 | `admin/src/services/payment.ts` |
| 契约 | 挂号状态机、支付超时、接诊台可见性 | `contracts/appointment-flow.json` |
| 契约 | 收费状态机与文案 | `contracts/payment-flow.json` |

## 核心代码走读

### 3.1 C 端：目录服务与确认下单

`miniprogram/services/directory.js:51-57` 把向导各步查询与下单统一收口在一组薄 API 封装里：

```js
function listSchedules(doctorId) {
  return request({ url: `/c/doctors/${doctorId}/schedules` })
}

function createAppointment(scheduleId) {
  return request({ url: '/c/appointments', method: 'POST', data: { schedule_id: scheduleId } })
}
```

注意目录查询全部携带 `city_code`——当前城市是医院与号源查询的硬边界，无本城市数据时页面走空态，不跨城市推荐。向导本身不含任何业务裁决，只做逐级选择与参数透传（如医生页把 `fee` 一路传到确认页展示）。

确认页的防重复提交是端侧双保险（`miniprogram/pages/booking/confirm/index.js:27-40`）：

```js
confirmBooking() {
  // loading 态禁用按钮 + 前置判断双保险，防重复提交
  if (this.data.submitting) return
  this.setData({ submitting: true })
  createAppointment(this.data.scheduleId)
    .then(() => {
      my.showToast({ content: '挂号成功，请尽快支付', type: 'success' })
      setTimeout(() => my.redirectTo({ url: '/pages/appointments/index' }), 800)
    })
    .catch((err) => {
      this.setData({ submitting: false })
      // request.js 已把 ApiException 错误体 detail 挂在 err.detail（如"号源已约满""请勿重复挂号"）
      const detail = (err && err.detail) || '挂号失败，请稍后重试'
```

端侧防重只是体验优化：真正的幂等与防超卖裁决都在 server-java（见 3.2/3.3）。失败时若错误含"号源"，确认页还会回调上级排班页 `loadSchedules()` 刷新余量，保证用户返回时该时段已显示约满。

### 3.2 挂号核心：行锁 + 幂等 + 预扣 + 对账（一个临界区）

`AppointmentService.reserve()`（`server-java/src/main/java/com/zhiyu/health/service/appointment/AppointmentService.java:71-118`）是整个模块的心脏：整个"Redis 预扣 + PG 写入"序列经 `SlotAccounting.withDeduction` 执行，PG 事务体内先做排班行锁，把幂等判断、序号分配与对账串成一个临界区：

```java
CreatedAppointment created = slotAccounting.withDeduction(
        scheduleId,
        deduction -> transactionTemplate.execute(status -> {
            // 排班行锁把幂等判断、序号分配与 PG 对账串成一个临界区，防止并发重复扣减或重号。
            Schedule schedule = scheduleMapper.selectByIdForUpdate(scheduleId);
            if (schedule == null || !Boolean.TRUE.equals(schedule.getIsActive())) {
                throw new ApiException(404, "排班不存在或已停用");
            }
            ...
            // 幂等检查通过后才预扣；售罄在此处抛 409 且 Redis 已被 SlotAccounting 回补。
            deduction.acquire();
            if (scheduleMapper.decrementRemainingSlots(scheduleId) != 1) {
                throw new ApiException(409, "号源已约满");
            }
```

要点：

- `SELECT ... FOR UPDATE` 行锁使同一排班的并发挂号串行化，幂等查重（同 profile 同排班且未取消）与序号分配（`nextSequenceNumber`）不会竞态。
- 幂等通过后才 `deduction.acquire()` 扣 Redis，再执行 PG 对账扣减；任一失败都抛异常触发事务回滚，Redis 预扣由 `SlotAccounting` 补偿回补。
- 挂号单落库即为 `pending_payment` 并写 `paymentDeadline`（`contracts.appointmentFlow().paymentTimeoutSeconds()`，演示 60 秒），号源在扣减时即被占住（"占位等支付"，ADR-0033）。

### 3.3 并发正确性专题：Redis 原子 DECR + PG 事务对账（禁止先查后改）

**第一层：Redis 原子 DECR。** `RedisSlotCounter`（`server-java/src/main/java/com/zhiyu/health/service/scheduling/RedisSlotCounter.java:19-30`）只做原子操作，没有任何读取-判断-写入序列：

```java
@Override
public long decrement(long scheduleId) {
    Long remaining = redisTemplate.opsForValue().decrement(key(scheduleId));
    if (remaining == null) {
        throw new IllegalStateException("Redis 号源扣减未返回结果");
    }
    return remaining;
}

@Override
public void increment(long scheduleId) {
    redisTemplate.opsForValue().increment(key(scheduleId));
}
```

DECR 是单条原子命令：即使两个请求同时打到同一排班，Redis 也会串行返回两个不同的余量值，不存在"先查后改"的丢失更新。键格式由 `SlotKeys.key()` 统一为 `schedule:{id}:remaining_slots`（`SlotKeys.java:16-18`）。

**第二层：判负即回补、PG 失败即补偿。** `SlotAccounting.Deduction`（`SlotAccounting.java:113-128`）：

```java
/** Redis 原子 DECR 预扣一个号源；判负即原子回补并抛出售罄，PG 侧尚未被触碰。 */
public void acquire() {
    long redisRemaining = slotCounter.decrement(scheduleId);
    if (redisRemaining < 0) {
        slotCounter.increment(scheduleId);
        throw new ApiException(409, "号源已约满");
    }
    acquired = true;
}

private void rollback() {
    if (acquired) {
        // Redis 不参与 PG 事务；PG 回滚或提交失败时只回补本次已经成功的预扣。
        slotCounter.increment(scheduleId);
    }
}
```

DECR 允许把计数扣成负数再判负：负数表示售罄，立刻 INCR 回补本次预扣并抛 409，PG 全程未被触碰。`acquired` 标志记录"本事务已成功应用的 Redis 变更"，事务体抛出（含 PG 提交失败）时只回补已成功部分——这就是 ADR-0011 说的"不多补不漏补"，也是它取代事故前 4+1 份手写 try-catch 变体的原因。

**第三层：PG 对账是条件 UPDATE，不是先查后改。** `ScheduleMapper.decrementRemainingSlots`（`server-java/src/main/java/com/zhiyu/health/mapper/scheduling/ScheduleMapper.java:138-144`）：

```java
@Update(
        """
        UPDATE schedules
        SET remaining_slots = remaining_slots - 1
        WHERE id = #{scheduleId} AND is_active = TRUE AND remaining_slots > 0
        """)
int decrementRemainingSlots(@Param("scheduleId") long scheduleId);
```

`WHERE remaining_slots > 0` 把"还有号"这个判断下推到 UPDATE 的原子语义里，返回受影响行数：返回 0 即对账售罄，service 抛 409 回滚并由 SlotAccounting 回补 Redis。若写成"先 SELECT 余量再 UPDATE"，两个并发事务可能读到同一旧值双双通过——这正是项目硬约束 4 禁止的模式。Redis 是高速闸门（挡住绝大多数超额请求），PG 条件 UPDATE 是最终裁决（账务真实来源），两者通过补偿保持一致。

### 3.4 支付状态机：B/C 端共用锁后流程

`PaymentService.payLocked()`（`server-java/src/main/java/com/zhiyu/health/service/appointment/PaymentService.java:64-84`）在收费行锁内执行支付状态机，C 端 `payForPatient`（按 patientId 归属校验后行锁）与 B 端 `payForAdmin`（按 paymentId 行锁）都汇到这个方法：

```java
String unpaid = contracts.paymentFlow().statuses().get("unpaid");
if (!unpaid.equals(payment.getStatus())) {
    throw new ApiException(409, contracts.paymentFlow().messages().get("already_paid"));
}
String paid = contracts.paymentFlow().statuses().get("paid");
String pendingPayment = contracts.appointmentFlow().status("pending_payment");
if (paymentMapper.markPaid(appointmentId, paid, unpaid, pendingPayment) == 0) {
    throw new ApiException(409, "挂号已取消或状态已变化，无法支付");
}
// 支付完成推进挂号单 PENDING_PAYMENT -> BOOKED（票 81）：CAS 只接受待支付。
// 模拟支付下不堆并发防御：返回 0 仅在支付与超时收敛极端竞态时出现，demo 不会触发。
Contracts.AppointmentFlow flow = contracts.appointmentFlow();
appointmentMapper.markBooked(appointmentId, flow.status("pending_payment"), flow.status("booked"));
```

行锁 + 状态检查 + 条件 UPDATE 三层防御：重复支付（B/C 两端同时点）由行锁串行化后到者拿 409；`markPaid` 要求 payment 仍 UNPAID 且挂号单仍 PENDING_PAYMENT；`markBooked` 的 CAS 保证超时已收敛（CANCELLED）的单不会被支付"复活"。状态与文案全部从 `contracts/payment-flow.json`、`contracts/appointment-flow.json` 读取，双栈共享同一事实源。

C 端支付入口在进状态机前先做惰性收敛（`AppointmentPaymentController.java:22-30`）：

```java
@PostMapping("/{appointmentId}/payment/pay")
public PaymentService.PaymentView pay(
        @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long patientId, @PathVariable long appointmentId) {
    // 支付入口惰性收敛：若该单已超时，先收敛为已取消并释放号源，
    // 再进入支付状态机--收敛后 payment 仍为 UNPAID，但挂号单已 CANCELLED，
    // markBooked 的 CAS 会拒绝推进，payForPatient 抛 409 提示状态变化。
    appointments.expireOverdueAppointments();
    return service.payForPatient(patientId, appointmentId);
}
```

controller 只做身份装配与入口编排，状态机全在 service——符合"controller 零业务逻辑"的分层约束。

### 3.5 超时惰性收敛：不引入调度中间件的号源释放

待支付单过期不依赖任何定时器，而是在 C 端列表/支付/取消与 B 端接诊台入口同步收敛（`AppointmentService.java:254-265`）：

```java
public void expireOverdueAppointments() {
    Contracts.AppointmentFlow flow = contracts.appointmentFlow();
    List<AppointmentMapper.OverdueAppointment> overdue =
            appointmentMapper.selectOverduePending(flow.status("pending_payment"));
    for (AppointmentMapper.OverdueAppointment item : overdue) {
        try {
            cancelOverdue(item.id(), item.scheduleId());
        } catch (RuntimeException ignored) {
            // 单条收敛失败不阻断其余条目；下次入口访问会再次尝试该条。
        }
    }
}
```

每条收敛（`cancelOverdue`，`AppointmentService.java:268-286`）是一条独立 `withRefund` 事务：`markCancelled` 的 CAS 只接受 PENDING_PAYMENT（并发重复收敛返回 0 即安全跳过），PG 回补 `remaining_slots` 成功后 `refund.grant()` 做 Redis INCR 退还；若事务失败，`Refund.revoke()` 撤销已退还的 Redis 增量。这套"惰性失效"与在线问诊 `expireOverdue` 同构（ADR-0033 明确否决了 `@Scheduled` 与 Redis keyspace notification），代价是号源释放时刻取决于下次入口访问——对 demo 与真实低峰场景都可接受。

### 3.6 B 端收费管理：契约驱动的列表与模拟支付

admin 侧状态、标签、文案全部从契约推导（`admin/src/pages/Payment/index.tsx:15-18`、39-44）：

```tsx
const statusColors = {
  [paymentStatuses.unpaid]: 'gold',
  [paymentStatuses.paid]: 'green',
} as Record<PaymentStatus, string>;
...
const pay = async (row: Payment) => {
  await payPayment(row.id);
  message.success(paymentMessages.pay_success);
  setDetail(undefined);
  await load();
};
```

服务层是三条薄封装（`admin/src/services/payment.ts:16-22`），直连 server-java `/api/b/payments`：

```ts
export const listPayments = (status?: PaymentStatus) =>
  request<Payment[]>('/api/b/payments', { params: status ? { status } : undefined });

export const getPayment = (id: number) => request<Payment>(`/api/b/payments/${id}`);

export const payPayment = (id: number) =>
  request<Payment>(`/api/b/payments/${id}/pay`, { method: 'POST' });
```

B 端"模拟支付"与 C 端患者支付最终都进入 `payLocked` 同一状态机（3.4），这体现了 ADR-0012 的边界：支付动作是 Mock（不接真实网关），但收费记录是真实 PG 实体，纳入 B 端管理面与审计。

## 契约与 ADR

- `contracts/appointment-flow.json`：挂号状态机（PENDING_PAYMENT/BOOKED/IN_PROGRESS/CANCELLED/VISITED）、pay/cancel 等迁移定义、`payment_timeout_seconds`（演示 60 秒）与接诊台可见性白名单；小程序在 `miniprogram/utils/appointment.js` 手工镜像。
- `contracts/payment-flow.json`：收费状态（UNPAID/PAID）、状态标签与支付文案（"该挂号收费已支付"），server-java 与 admin 双栈只读消费。
- `docs/adr/0007-slot-pool-counting.md`：号源用池计数（不建逐号实体），扣减 = Redis 原子 DECR + PG 事务对账，挂号单记录分配序号。
- `docs/adr/0011-slot-accounting-compensation.md`：号源补偿收敛为 `SlotAccounting` 命令式句柄，唯一操作 `SlotCounter`（ArchUnit 强制），取代事故前 4+1 份补偿变体。
- `docs/adr/0012-real-entities-mock-payment.md`：挂号收费（`payments` 表）为真实业务实体，支付动作维持 Mock 状态机，不接支付网关。
- `docs/adr/0033-appointment-payment-gating-and-single-call.md`：挂号即待支付并占位号源、支付推进待就诊、超时惰性收敛释放号源、接诊台单叫号约束。

## 讲解提示

- **教学强调点 1：为什么"禁止先查后改"。** 让学生对比两种写法：`SELECT remaining_slots` 后判断再 `UPDATE`，与 3.3 的 `UPDATE ... WHERE remaining_slots > 0` 返回行数判断。前者在两个并发事务下会因读到同一快照而双双通过造成超卖；后者把判断并入单条语句的原子语义。Redis DECR 同理——DECR 本身原子返回扣后值，判负回补即可，不需要先 GET。
- **教学强调点 2：分布式两存储没有共同事务时怎么办。** Redis 不参与 PG 事务，所以"Redis 预扣 + PG 写入"必须显式补偿。`SlotAccounting` 的句柄（Deduction/Refund/Adjustment/Initialization）把"本次已成功应用的 Redis 变更"显式化为状态字段，失败时只反向补偿已成功部分——可让学生数一遍 3.3 中每条失败路径（判负、PG 对账返回 0、事务异常）各自的补偿动作。
- **常见提问：为什么不用数据库唯一约束或乐观锁就够了，还要 Redis？** 答案要点：Redis 原子 DECR 是高速闸门，把超额请求挡在 PG 之外（热门排班秒杀式挂号时保护数据库）；PG 条件 UPDATE 是最终裁决保证不超卖；两者职责分层，靠 SlotAccounting 补偿维持一致。这也是 ADR-0007 自述的"高并发架构唯一真实落地点"。
- **常见提问：支付超时为什么不用定时任务？** 答案要点：ADR-0033 否决了 `@Scheduled`（项目无调度先例）与 Redis keyspace notification（需改云端 compose 且事件不可靠）；惰性收敛把释放动作挂在患者列表/支付/取消与接诊台这些必然被访问的入口上，零新中间件，与在线问诊超时收敛同构。代价（释放延迟到下次访问）在演示场景可忽略，且 C 端倒计时归零后会自动刷新列表触发收敛。
