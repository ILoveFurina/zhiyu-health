# 35 - 挂号收费：诊查费字段与挂号即欠费

**What to build:** doctors 表新增挂号费字段（按职称定价）并补 seed；appointments 表新增挂号费快照字段；新增 payments 表（只承载挂号收费，关联 appointment_id）；新增 contracts/payment-flow.json 契约；挂号成功时在号源扣减事务后产生 UNPAID 收费记录（不阻塞号源扣减，收费记录写入失败不回滚挂号，与病情摘要同模式）；C 端挂号卡片与"我的挂号"页展示费用与支付状态。此票与药品模块完全解耦，可并行。

**Blocked by:** None - can start immediately

**Status:** claimed

- [x] doctors 表新增 `registration_fee DECIMAL(10,2)`，seed 按职称定价（主任医师 50 / 副主任医师 30 / 主治医师 20）
- [x] appointments 表新增 `registration_fee DECIMAL(10,2)`，挂号时从 doctors 快照写入
- [x] 新增 `payments` 表（id, appointment_id FK unique, amount, status default 'UNPAID', created_at, paid_at；status CHECK IN ('UNPAID','PAID')），只承载挂号收费
- [x] 新增 `contracts/payment-flow.json`（statuses unpaid/paid、status_labels、decisions pay、messages），格式参照 contraindication.json；Contracts 启动期加载
- [x] 挂号成功（AppointmentService.create 事务内或紧随其后）产生 UNPAID 收费记录，金额 = appointments.registration_fee；不改动现有 SlotAccounting 号源扣减逻辑；收费记录写入失败不回滚挂号
- [x] AppointmentCardBase / AppointmentOut / Agent AppointmentCard 三处 record 增 registration_fee 与支付状态字段；C 端"我的挂号"卡片展示费用与支付状态
- [x] MockMvc 验证：挂号成功后存在 UNPAID 收费记录，金额等于医生职称价；appointments 接口返回费用与支付状态
- [ ] 浏览器实测无控制台错误，人工走通"挂号 -> 我的挂号看费用"

## Comments

- 2026-08-02：实现位于隔离 worktree `E:\project\zhiyu-health-t35`、分支 `codex/t35-registration-fee`。server-java 207 tests passed；server-py 84 passed / 2 skipped，ruff、mypy、import-linter passed；Spotless check passed。用户明确要求跳过前端人工验收，因此状态保留 `claimed`，README 完成标记未更新。
