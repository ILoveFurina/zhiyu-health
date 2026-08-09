# OTC 药品无处方直接下单，处方药凭已审核处方下单

Status: accepted

AI 购药需支持两条路径：OTC（非处方药）由用户在对话中点名药品后直接下单，处方药凭已审核（APPROVED）电子处方下单。现有 `drug_orders.prescription_id NOT NULL` 与 `DrugOrderService.create` 硬绑处方，OTC 无处方可挂，无法落地。

决策：`drug_orders.prescription_id` 改为可空，单表承载两类订单。处方药订单 `prescription_id` 非空且药品须属该处方明细，OTC 订单 `prescription_id` 为空且药品须 `is_prescription=FALSE`，两类约束由 `DrugOrderService` 分支强校验。库存预扣/取消回补/支付状态机完全复用，不另建表。`medications` 新增 `is_prescription BOOLEAN NOT NULL DEFAULT TRUE` 区分两类，DEFAULT TRUE 偏安全（拿不准当处方药）。下单经两段式：Agent 产出购药确认卡（kind=`drug_order_confirm`，不扣库存），用户确认后 C 端直接调 `POST /api/c/drug-orders` 建单（不经 Agent 工具），结果以 `drug_order` kind 卡片回落。

被否决的方案：

- OTC 单独建表 `otc_drug_orders`：B 端订单管理要查两表合并、库存逻辑写两遍、与"药品订单"单一领域概念冲突。
- OTC 也造假处方：破坏"电子处方"严肃性，污染 ADR-0030 驳回终态等约束。

边界（硬约束 2）：Agent 只在用户明确点名药品时触发 OTC 下单，用户仅描述症状时走"通用药品知识解释"不主动推荐并下单；数量由用户明确给出，Agent 不推断不默认；确认卡是下单唯一入口，Agent 不直接扣库存。
