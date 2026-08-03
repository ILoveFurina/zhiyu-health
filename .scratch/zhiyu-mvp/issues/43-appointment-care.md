# 43 - 挂号后关怀消息 + 就诊指引卡

**What to build:** 挂号成功后由 server-java 在 `AppointmentService.create()` 事务内向 `in_app_messages` 写一条 type=`appointment_care` 的结构化关怀消息（含就诊指引卡：地址/楼层/携带材料/注意事项），覆盖 C 端 Agent 与 B 端直接挂号所有入口，靠 `UNIQUE(related_appointment_id, type)` 幂等。C 端消息页按 type 渲染为卡片。

**Blocked by:** 07 - 挂号闭环；09 - 电子处方（站内消息通道）

**Status:** ready-for-agent

- [ ] `schema.sql`：`hospitals` 加 `address/floor/precautions/materials` 四列；seed 填虚构静态值
- [ ] 新建 `contracts/appointment-care.json`：`message_type=appointment_care` + content schema（greeting/hospital_name/department_name/doctor_name/schedule_time/address/floor/materials[]/precautions[]）
- [ ] server-java `AppointmentService.create()`：事务内联查 hospitals+排班拼装 content，INSERT `in_app_messages`（type=`appointment_care`，disclaimer 注入）；覆盖所有挂号入口
- [ ] 幂等：重复挂号请求靠 `UNIQUE(related_appointment_id, type)` 不重复写
- [ ] 端侧 `pages/messages/index.{axml,js,acss}`：加 `appointment_care` type 分支渲染卡片（地址/楼层/材料列表/注意事项列表），底部 disclaimer
- [ ] MockMvc 覆盖：挂号成功即写关怀消息 + 重复挂号不重复写；端侧浏览器实测消息页渲染卡片

## Comments

### 2026-08-03 - grill-with-docs 设计澄清

原票 20（情感化包）拆为 43/44/45 三票，本票承接原票 20 的"挂号后主动关怀消息"+"就诊指引卡"两项。决策与 checklist 同等约束力：

- **数据来源**：地址/楼层/携带材料/注意事项来自 `hospitals` 表新增四列的静态 seed 值，**非 LLM 生成**（医疗场景地址错误后果重，演示用虚构医院静态值）。
- **产物形态**：一条 `in_app_messages`（type=`appointment_care`），`content` 存结构化 JSON。不拆两条消息、不进对话流（对话流是 Agent 产出，指引卡是挂号副作用，对齐票 19 D2 精神）。
- **写入方/时机**：server-java `AppointmentService.create()` 事务内写，覆盖 C 端 Agent 与 B 端直接挂号所有入口；挂号成功则消息就绪，失败一起回滚无悬空。
- **契约**：`contracts/appointment-care.json` 是双栈共享常量单一事实源；type 名与 content schema 均在此定义。
- **端侧**：messages 页当前是平铺文本，加一个 `appointment_care` type 分支渲染卡片，底部沿用 in_app_messages 的 disclaimer 列（硬约束 1 无例外）。
- CONTEXT.md 已补"就诊指引卡"术语；不新增 ADR（加列/一条消息/卡片渲染均不难逆转、不令人意外、无真实替代，三判据不全真）。
