# 78 - 处方药选处方交互

**What to build:** 补全处方药购药路径的多处方选择分支（77 已闭环 OTC 与单处方直通）。当 `list_approved_prescriptions` 返回多张 APPROVED 处方时，Agent 产出处方选择卡让用户点选：复用 department_options 的 chip 点选形态或轻量列表，每项展示处方来源（开方医生+日期）+ 药品摘要（药名/规格/数量），用户点选某处方后进入 prepare_drug_order -> 确认卡 -> 下单 -> 结果卡（与 77 单处方直通共用后半段）。零处方（患者无 APPROVED 处方）时 Agent 文字引导"您暂无已审核处方，可先发起问诊或挂号让医生开方"，不出卡。处方视图带药品明细（来自 75 的 list_approved_prescriptions 返回体）供选择卡直接展示，用户无需点进去看。此票是 77 的增量，完成后处方药路径多/单/零三态全覆盖。

**Blocked by:** 77 - C 端购药卡片渲染与两段式确认交互

**Status:** done

- [x] miniprogram 新增处方选择卡渲染（chip 点选或轻量列表）：每项展示医生姓名+日期+药品摘要（药名/规格/数量），点选后进入确认卡流程；可复用 department_options chip 形态
- [x] chat/index.axml 分发：处方选择卡渲染 + ai-disclaimer
- [x] server-py agent 接线：list_approved_prescriptions 返回多张时产出处方选择卡（候选 id 列表）；返回单张时直通确认卡（77 已实现）；返回零张时文字引导不出卡
- [x] 多处方端到端：患者有2张以上 APPROVED 处方 -> 用户"按处方买药" -> 处方选择卡 -> 点选某处方 -> 确认卡（标注所选处方来源）-> 下单 -> 结果卡
- [x] 零处方端到端：患者无 APPROVED 处方 -> 用户"按处方买药" -> Agent 文字引导"暂无已审核处方，可先发起问诊或挂号"，不出卡不下单
- [x] 浏览器/开发者工具实测无控制台错误，人工走通多处方选择与零处方引导；选择卡视觉层级清晰
- [x] README.md 依赖关系图新增节点 T78（未完成不加 [x]）
