# 37 - 药品订单：处方驱动下单与库存预扣

**What to build:** 新增药品订单表与契约；C 端在已审核（APPROVED）电子处方详情页点击购药，系统基于处方明细生成订单（药品 + 数量 + 单价，数量默认 1 可改）；下单时以 PostgreSQL 行锁 + 条件更新扣减 medications.stock（库存不足则下单失败），取消未支付订单回补库存；C 端新增"我的药品订单"页查看订单及状态。替换现有纯前端 Mock 购药按钮为真实后端下单。库存预扣与号源机制不同：药品购药并发度低，用 PG 行锁即可防超卖，不引入 Redis 计数。

**Blocked by:** 34 - 药品管理：价格库存字段与 B 端管理面

**Status:** done

- [x] 新增 `drug_orders` 表（id, patient_id, prescription_id, status default 'UNPAID', total_amount, created_at, paid_at, cancelled_at；status CHECK IN ('UNPAID','PAID','DONE','CANCELLED')）
- [x] 新增 `drug_order_items` 表（id, drug_order_id FK cascade, medication_id FK, quantity, unit_price, subtotal）
- [x] 新增 `contracts/order-flow.json`（statuses unpaid/paid/done/cancelled、status_labels、decisions pay/cancel/complete、message_types、messages），格式参照 prescription-flow.json
- [x] C 端下单接口：传 prescription_id + 数量，校验处方为 APPROVED，订单明细复用处方明细（药品 + 数量 + 单价取 medications.price），计算 total_amount
- [x] 库存预扣：下单时 `UPDATE medications SET stock = stock - n WHERE id = ? AND stock >= n`，affected rows = 0 即库存不足下单失败（禁止先查后改）；取消未支付订单回补库存（事务内）
- [x] C 端购药入口替换 Mock：`miniprogram/pages/prescriptions/index.js` 的 order() 改为真实下单调用；新增"我的药品订单"页并在 app.json 注册
- [x] MockMvc 验证：下单成功扣库存、状态 UNPAID；库存不足下单失败且无订单创建；取消未支付订单库存回补
- [x] UI 验收由用户明确要求本次跳过；未声明浏览器无控制台错误或人工链路已走通

## Comments

- 2026-08-02：server-java 全量 210 个测试、Spotless、小程序 JS 语法与 JSON 解析通过；用户明确要求跳过 UI 验收。双轴审查发现的 2 项规范问题、1 项规格问题与 1 项轻微异味均已修复。
