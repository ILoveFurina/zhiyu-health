# 叫号时段约束与演示时段覆盖

Status: accepted（票 87 接诊台叫号时段约束与操作顺序）

线下接诊台叫号需约束在医生出诊时段内，否则演示会出现"午休/下班后仍可叫号"的不真实操作；同时为支持任意时刻可完整演示线下链路（如上午场景演示走到下午），需要可临时调整时段窗口。两者一并落到 `SlotWindowGuard.isWithinWindow`（叫号判断）与 `EffectiveSlotWindows`（窗口事实源）。

决策：

1. **叫号时段窗口（闭区间含起止）**：医生只能在当前时间处于该挂号所属排班时段窗口内时叫号，窗口为闭区间（`!now.isBefore(start) && !now.isAfter(end)`），即 `09:00:00` 与 `11:30:00` 两个边界时刻可叫、`11:30:00.001` 不可叫。契约 `time_slot_windows`（上午 09:00-11:30 / 下午 14:00-18:00）是挂号时段截止校验与叫号时段校验的**单一事实源**，server-java 在挂号时判断是否已过 `end`、在叫号时判断是否落在 `[start, end]`。
2. **未知时段 fail-closed**：契约未定义窗口的时段（`window == null`）与 `null` 输入一律不可叫号（返回 `false`），只有契约或演示覆盖显式定义了窗口的时段才可叫。这与 C 端挂号截止的 `isClosed`（未知时段不阻断、安全返回 `false`）相反--挂号截止容错放行，叫号严格收口。
3. **过点滞留不另引入状态**：过点未叫号的待就诊患者保持 `BOOKED` 待就诊、不可叫号，不引入爽约状态；`ReceptionService.call` 对过点滞留抛 409"当前不在该挂号所属出诊时段内，暂不可叫号"。队列全部显示，非当前时段行置灰并禁用叫号按钮（`callable` 标记由后端计算下发，前端不自行复制时段表）。
4. **约束只拦叫号**：完成接诊（`complete`）与查看处方不拦时段。配合票 87 另一条硬约束--`complete` 废弃 `BOOKED -> VISITED` 直通、只接受 `IN_PROGRESS -> VISITED`（见 ADR-0033 修订），叫号成为进入就诊中的唯一入口，跳过叫号无法完成接诊，时段约束因此在叫号这一环闭环。过点后医生未完成接诊的"就诊中"单由系统惰性收敛为"已接诊"（ADR-0039，票 94），释放单叫号约束--这是过点收尾，不与"完成接诊不拦时段"冲突（医生过时段后仍可手动 complete，系统收敛只处理忘了点的滞留单）。
5. **演示时段覆盖（ADR-0022 模式）**：演示工具箱 `/api/b/demo/time-slot-windows`（GET/PUT）可覆盖上午/下午起止，`AdminInterceptor` 强制 admin 鉴权 + env `DEMO_TIME_SLOT_ENABLED` 门控（默认 `false`），开启后写入 Redis 键 `demo:time_slot_windows`（契约 `demo-arsenal.json` 定义键名）。`EffectiveSlotWindows` 读取顺序为演示覆盖优先、契约兜底。
6. **fail-safe**：演示开关关闭时忽略 Redis 残留覆盖，恒返回契约窗口，生产语义不变；开关开启但 Redis 覆盖缺失/损坏/非法时也回退契约窗口，不让脏覆盖影响挂号/叫号硬约束。写前与读回退共用同一 `isValid` 校验（恰好包含契约全部时段键且每段 `start < end`），保证落库值与生效值口径一致。

## 被否决的方案

- **前端按排班时段表本地判断可叫**：时段窗口会随演示覆盖变化，前端复制一份会与后端事实源漂移；且时段表语义（排班申请审核的时段定义）与叫号窗口语义（当前可叫的时间区间）耦合两套职责。改为后端计算 `callable` 下发，前端只渲染。
- **叫号窗口另起一套契约键**：挂号截止与叫号判断本就基于同一组 `time_slot_windows`，拆两套会出现"已过挂号截止但仍可叫"或反之的不一致。收敛为 `EffectiveSlotWindows` 单一事实源，`isClosed` 与 `isWithinWindow` 共用。
- **演示覆盖用 Spring Profile**：项目无 `@Profile` 基础设施，env 变量是既有唯一机制（ADR-0022 先例），沿用 `DEMO_TIME_SLOT_ENABLED` + `application.yml` `${VAR:default}`。
- **过点未叫号引入爽约状态**：爽约会改变状态机与表结构，且 demo 场景无业务价值（过点患者保持待就诊、不可操作即可表达"无法再叫"），不新增状态。

## Consequences

- `EffectiveSlotWindows` 成为挂号截止（C 端 `AppointmentService`）与叫号窗口（B 端 `ReceptionService`）的共享事实源，两者窗口口径永远一致；后续窗口语义变更只改一处。
- 演示时段覆盖是临时的、Redis 侧的、env 门控的：关闭 env 后立即失效，无持久副作用；不写库、不改契约，`schema.sql` 与状态机不变（票 87 无 schema 变更）。
- `SlotWindowGuard.isWithinWindow` 与 `ReceptionService.call` 的时间经注入的 `Clock` 读取，测试可固定时钟覆盖上午/下午边界与过点滞留场景；`EffectiveSlotWindows` 在 env 关闭时不触碰 Redis，单测可纯契约验证 fail-safe。
- 叫号窗口只拦 `call`：医生在非时段内仍可查看已接诊患者的处方、仍可对已在就诊中的患者完成接诊，不因时段误锁进行中的诊疗流程。
