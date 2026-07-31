# 36 - 挂号收费：模拟支付与 B 端收费管理

**What to build:** C 端对未支付挂号收费执行模拟支付（UNPAID -> PAID），不接支付网关；B 端新增收费管理页，按状态筛选收费记录、查看明细、对待支付记录执行模拟支付。支付动作为模拟状态机，一键置为已支付。

**Blocked by:** 35 - 挂号收费：诊查费字段与挂号即欠费

**Status:** ready-for-agent

- [ ] C 端挂号收费模拟支付接口（`/api/c/appointments/{id}/payment/pay`），UNPAID -> PAID 状态流转，使用 contracts/payment-flow.json 常量
- [ ] B 端新增收费管理页（`admin/src/pages/Payment/`）：按状态筛选（未支付/已支付）+ 查看明细 + 模拟支付操作；routes.ts 追加菜单项并加入 ADMIN_PATHS
- [ ] B 端收费管理接口（`controller/b/`）：列表（按状态筛）+ 明细 + 模拟支付
- [ ] MockMvc 验证：模拟支付后 payments.status 为 PAID、paid_at 已记录；挂号卡片支付状态同步更新
- [ ] 浏览器实测无控制台错误，人工走通"挂号 -> 看费用 -> 模拟支付 -> B 端收费管理可见已支付"
