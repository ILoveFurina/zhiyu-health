# 79 - C 端购药卡片渲染与两段式确认交互

**What to build:** miniprogram 新增两种卡片组件并打通 AI 购药两条端到端路径。① `drug_order_confirm` 购药确认卡：展示药品明细（名/规格/单价）、数量、总价、库存可用性，处方药路径额外标注处方来源（医生+日期），底部"确认下单"与"取消"按钮；卡片就地确认，不跳独立页（区别于挂号跳 /pages/booking/confirm）。② `drug_order` 购药结果卡：展示订单号、状态、总价、药品明细、支付/取消入口。用户在确认卡点"确认下单"后，C 端直接调 `POST /api/c/drug-orders`（OTC 路径 prescription_id=null + items；处方药路径 prescription_id + items），不经 Agent 工具，server-java 预扣库存建单，结果以 drug_order 卡片回落。两条路径：OTC--用户对话明确点名药品+数量 -> search_medications -> prepare_drug_order -> 确认卡 -> 下单 -> 结果卡；处方药单处方直通--list_approved_prescriptions(单张) -> prepare_drug_order -> 确认卡 -> 下单 -> 结果卡（多处方选择分支在 80）。三条硬边界必须守：Agent 只在用户明确点名药品时触发 OTC、数量由用户明确给出 Agent 不推断不默认、确认卡是下单唯一入口 Agent 不直接扣库存。**可视化做好**为硬验收项：药品明细/价格/库存/处方来源/状态流转要有清晰视觉层级。

**Blocked by:** 77 - server-py 购药工具与 agent 接线；78 - 购药卡片契约与 SSE 事件

**Status:** done

- [x] miniprogram 新增 drug_order_confirm 卡片组件：药品明细（名/规格/单价）、数量、总价、库存可用性、处方来源（处方药路径）、确认下单/取消按钮；就地确认不跳页
- [x] miniprogram 新增 drug_order 卡片组件：订单号、状态标签、总价、药品明细、支付/取消入口（复用现有 drug-orders 页的支付/取消逻辑）
- [x] chat/index.axml 卡片分发：item.kind === 'drug_order_confirm' / 'drug_order' 各自渲染对应组件 + ai-disclaimer
- [x] 确认卡"确认下单"逻辑：组装 CreateInput（OTC: prescription_id=null + items[{medication_id,quantity}]；处方药: prescription_id + items）调 POST /api/c/drug-orders；成功后确认卡就地更新或追加 drug_order 结果卡；失败提示库存不足等
- [x] OTC 端到端：用户"帮我买2盒布洛芬" -> Agent search_medications -> prepare_drug_order -> 确认卡 -> 用户确认 -> 下单 -> 结果卡（真机或模拟器走通）
- [x] 处方药单处方直通：用户"按处方买药"且仅有1张 APPROVED 处方 -> Agent list_approved_prescriptions -> prepare_drug_order -> 确认卡（标注处方来源）-> 用户确认 -> 下单 -> 结果卡
- [x] 硬边界守护验证：用户只说症状（"我头痛"）时 Agent 走通用药品知识解释不出确认卡不下单；用户没给数量时 Agent 反问不默认1
- [x] 浏览器/开发者工具实测无控制台错误，人工走通两条路径；可视化视觉层级清晰（药品/价格/库存/状态一目了然）
- [x] README.md 依赖关系图新增节点 T79（未完成不加 [x]）
