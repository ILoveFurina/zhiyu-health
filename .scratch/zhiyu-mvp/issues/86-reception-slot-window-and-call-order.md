# 86 - 接诊台叫号时段约束与操作顺序

**What to build:** 收紧线下接诊台操作语义，两条硬约束：①操作顺序——接诊完成只能从"就诊中"推进，废弃 `BOOKED -> VISITED` 直通兜底（ADR-0033 修订）；②叫号时段窗口——医生只能在当前时间处于该挂号所属排班时段窗口（有效时段窗口，默认契约 `time_slot_windows` 上午 09:00-11:30 / 下午 14:00-18:00，闭区间含起止）内叫号。配合 B 端接诊台队列置灰与演示工具箱"时段设置"覆盖，保证任意时刻可完整演示线下链路。

**Blocked by:** 71 - 线下叫号通知与挂号凭证（已 done）

**Status:** done

- [x] contracts：`appointment-flow.json` 的 `complete.from` 收敛为仅 `IN_PROGRESS`，文档注明废弃直通；`ContractsTest` 同步；miniprogram 手工镜像核对（`miniprogram/utils/appointment.js`，无影响则注明）
- [x] server-java：时段窗口解析收敛为"有效时段窗口"（演示覆盖优先、契约兜底；env 关闭时忽略 Redis 残留覆盖，fail-safe）；`ReceptionService.call` 加窗口校验（含起止闭区间、午休/下班不可叫、未知时段 fail-closed），`complete` 拒绝 BOOKED 直通（409）
- [x] server-java：接诊台 dashboard 为每行返回是否可叫号标记（如 `callable`），前端不自行复制时段表
- [x] demo：`/api/b/demo/**` 新增"时段设置"（admin + env `DEMO_TIME_SLOT_ENABLED` 门控，默认 false），可配置上午/下午起止，非法值（start >= end）拒绝；`application.yml` 绑定 `${DEMO_TIME_SLOT_ENABLED:false}`，`.env.example` 写 `false`，本地 `.env` 写 `true`（用户已确认）
- [x] B 端：`ReceptionQueue` 接诊按钮仅"就诊中"可点（BOOKED 只显示叫号）；全部患者显示，非当前时段行标"非当前时段"并禁用叫号；已接诊/有处方仍走"查看"
- [x] 测试：service 单测覆盖叫号时段边界（09:00:00 / 11:30:00 / 11:30:00.001 / 午休 / 18:00 后 / 未知时段）、过点滞留患者不可叫、BOOKED complete 409、env 关闭忽略覆盖；契约一致性；MockMvc 主链路冒烟；admin typecheck/build
- [x] 文档：修订 ADR-0033（移除 `complete 保留 BOOKED -> VISITED 直通兜底` 后果）；新增 ADR 记录叫号时段约束与演示时段覆盖；`CONTEXT.md` 词条已在 grilling 会话中更新（单叫号约束 / 接诊 / 叫号）
- [x] 无 schema 变更（状态机与表结构不变），无需 `reset_zhiyu.py`
- [x] 票单置 done 前：README 依赖图 `T86` 节点加 `[x]`

## Comments

- 2026-08-10 grilling（grill-with-docs + domain-modeling）收敛以下决策：①废弃 BOOKED→VISITED 直通；②"接诊"=打开抽屉并完成，叫号即进入就诊中，不新增开始接诊动作；③时段约束只拦叫号，完成接诊与查看不拦；④过点未叫号患者保持待就诊、不可操作，不引入爽约状态；⑤窗口为闭区间含起止，未知时段不可叫号；⑥队列全部显示、非当前时段置灰；⑦演示工具箱可覆盖上午/下午起止（ADR-0022 模式），C 端挂号截止同步走有效时段窗口。
