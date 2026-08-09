# 挂号支付门控与单叫号约束

挂号成功即进入待支付（PENDING_PAYMENT）并占用号源，支付完成才推进为待就诊（BOOKED）并对接诊台可见；待支付有支付截止（演示默认 60 秒），过期惰性收敛为已取消并释放号源；接诊台同一医生同时只能一条就诊中，完成接诊后才能叫下一个号。不引入调度中间件，号源扣减沿用 SlotAccounting 占位等支付模型，直挂号与 AI 引导挂号两条路径统一走待支付（修订票 41 直挂号免支付边界）。

## Considered Options

- **号源时机**：占位等支付（待支付即扣号源，超时/取消释放）vs 支付才占号。选前者：硬约束 4 防超卖零改动，SlotAccounting 复用 withDeduction/withRefund；后者需新增 held 计数器、把防超卖推到支付时，全栈地震。
- **超时触发**：惰性失效（复刻在线问诊 expireOverdue，入口同步收敛）vs Spring @Scheduled vs Redis keyspace notification。选惰性失效：与 `contracts/online-consultation.json` 既定「不引入调度中间件」同构，零新中间件；@Scheduled 打破项目无调度先例；keyspace notification 需改云端 compose.yaml（违反运行拓扑硬约束）且事件不可靠。
- **叫号约束范围**：医生维度（跨当天所有排班）vs 单排班维度。选医生维度：接诊现实是医生一次只看一个患者，单排班维度下同医生上午+下午排班能同时叫两个号，与需求冲突。
- **状态 key 命名**：新增 PENDING_PAYMENT、保留 BOOKED 只改标签（已约->待就诊）vs 连 key 重命名。选前者：BOOKED/IN_PROGRESS/VISITED/CANCELLED 四个 key 在 mapper CAS 子句、ArchUnit、契约测试、admin 直读 JSON、小程序手镜里到处硬编码，动 key 是全栈地震，动标签只改 appointment-flow.json + 小程序 + CONTEXT.md。

## Consequences

- `complete` 只接受 `IN_PROGRESS -> VISITED`，废弃原 `BOOKED -> VISITED` 直通兜底（票 86 修订）：叫号是进入就诊中的唯一入口，跳过叫号直接完成接诊会绕过时段窗口校验与就诊中占位，破坏单叫号约束；代码侧由 `complete.from` 收敛为 `["IN_PROGRESS"]` 强制（契约 + `ContractsTest` 钉死）。
- 支付超时取消的单在「我的挂号」显示已取消、可重挂同一排班（沿用 `uq_appointments_profile_schedule_active` 偏唯一索引排除 CANCELLED 的语义），不发站内消息（与在线问诊 EXPIRED 先例一致）。
- 接诊台可见性白名单为 `status IN (BOOKED, IN_PROGRESS, VISITED)`，待支付对医生不可见；收敛查询与可见查询分离--接诊台入口先全局 expireOverdueAppointments() 再查可见列表。
