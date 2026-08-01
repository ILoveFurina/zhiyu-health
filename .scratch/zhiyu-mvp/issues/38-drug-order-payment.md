# 38 - 药品订单：模拟支付、状态流转与 B 端订单管理

**What to build:** C 端对未支付药品订单执行模拟支付（UNPAID -> PAID）；B 端新增药品订单管理页，按状态筛选订单、查看明细、取消未支付订单、将已支付订单标记为已完成（PAID -> DONE）。药品订单的支付状态内嵌在订单状态机中，不进 payments 表（与挂号收费解耦）。

**Blocked by:** 37 - 药品订单：处方驱动下单与库存预扣

**Status:** ready-for-agent

- [ ] C 端药品订单模拟支付接口（UNPAID -> PAID），取消未支付订单接口（UNPAID -> CANCELLED + 库存回补），使用 contracts/order-flow.json 常量
- [ ] B 端新增药品订单管理页（`admin/src/pages/DrugOrder/`）：按状态筛选（待支付/已支付/已完成/已取消）+ 查看明细 + 取消未支付 + 确认完成（PAID -> DONE）；routes.ts 追加菜单项并加入 ADMIN_PATHS
- [ ] B 端药品订单管理接口（`controller/b/`）：列表（按状态筛）+ 明细 + 取消 + 确认完成
- [ ] MockMvc 验证：UNPAID -> PAID -> DONE 状态流转；取消未支付订单状态为 CANCELLED 且库存已回补
- [ ] 浏览器实测无控制台错误，人工走通"C 端下单 -> 模拟支付 -> B 端订单管理确认完成"
