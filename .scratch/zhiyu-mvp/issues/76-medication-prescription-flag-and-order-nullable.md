# 76 - 药品处方属性与订单处方可空（schema 与 service 基线）

**What to build:** 为 AI 购药做 schema 与 service 层的 expand 基线，不破坏现有处方药订单路径。`medications` 加 `is_prescription BOOLEAN NOT NULL DEFAULT TRUE`（DEFAULT TRUE 偏安全：拿不准当处方药，须凭处方），seed 30 个药品按医学实际标值（抗生素/抗凝/心血管/降糖/PPI/呼吸处方/甲状腺类共 22 个 TRUE；常见 OTC 解热镇痛/抗过敏/止泻/促胃动力/维生素钙共 8 个 FALSE）；`drug_orders.prescription_id` 改可空；`DrugOrderService.create` 拆 OTC / 处方药两分支校验（OTC 路径 prescription_id 必空且药品须 `is_prescription=FALSE`，处方药路径 prescription_id 必填且药品须属该处方明细），库存预扣/取消/支付状态机完全复用不另写；契约 `order-flow.json` 加 `source` 枚举（`prescription`/`otc`）区分订单来源；B 端 MedicationForm 加 `is_prescription` 编辑项。此票完成后旧处方药订单路径全绿，OTC 路径 service 层就位但暂无 C 端入口。ADR-0032 已记录决策，CONTEXT.md"药品订单"/"AI 购药"/"购药确认卡"词条已更新。

**Blocked by:** None - can start immediately

**Status:** done

- [x] schema.sql：`medications` 加 `is_prescription BOOLEAN NOT NULL DEFAULT TRUE`（幂等 `ADD COLUMN IF NOT EXISTS`）+ 注释；`drug_orders.prescription_id` 改可空（幂等 ALTER）
- [x] seed.sql：30 个药品 INSERT 补 `is_prescription` 值（处方药 22 个 TRUE / OTC 8 个 FALSE），保持 INSERT 列顺序与 schema 一致
- [x] Medication entity + MedicationMapper 查询补 is_prescription 字段；DrugOrder entity 的 prescriptionId 注释更新为可空
- [x] DrugOrderService.create 拆双分支：`prescriptionId == null` 走 OTC（校验每个 medication 的 is_prescription=FALSE、行锁预扣库存、quantity 由入参给出）；`prescriptionId != null` 走现有处方校验链路不变；两分支共用库存预扣/取消/支付
- [x] DrugOrderController.CreateInput 的 prescriptionId 改可空（去掉 @NotNull）；inputMapper 透传 null
- [x] contracts/order-flow.json 加 `source` 枚举（`prescription`/`otc`）与 label；OrderView 增 source 派生展示字段
- [x] B 端 MedicationController.MedicationInput + MedicationForm 表单加 is_prescription 编辑项（admin 可改）；list 接口返回该字段；列表页加"类型"列展示处方药/OTC
- [x] server-java service 单测：OTC 下单成功（prescription_id=null、is_prescription=FALSE）；OTC 下单处方药被拒（is_prescription=TRUE 抛 409）；处方药下单 OTC 药品被拒（药品不在处方明细抛 400，复用现有断言）；库存预扣/取消回补两路径都对
- [x] 运行 `uv run python scripts/reset_zhiyu.py` 重建云演示库，重启 server-java，`uv run python scripts/verify_zhiyu.py` 验证 schema 形状与 seed 基线
- [x] README.md 依赖关系图 T76 节点加 [x]
