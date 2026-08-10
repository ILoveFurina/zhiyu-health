/**
 * 查药结果卡（票 77/79，票 93 收敛）：server-py 经 search_medications 工具产出，server-java 透传落库。
 * 只读展示按药名模糊查到的在售非处方药（OTC）清单，供用户点名买药时参考；不交互、不下单
 * （下单由 Agent 自主调 prepare_drug_order -> 确认卡 -> 结果卡，不把选药交给用户，避免与
 * Agent 自主性产生双路径）。
 *
 * 票 88（ADR-0035）起价格/库存/在售语义下沉到院区药房（pharmacy_medications），本卡不再展示
 * 这些字段，只展示标准目录身份信息；具体在售状态与价格以下单页为准。
 *
 * content JSON 字段（与 server-java MedicationToolService.MedicationView 对齐，SNAKE_CASE）：
 *   medications[{medication_id, name, generic_name, specification}]
 * 免责声明由宿主模板的 ai-disclaimer 渲染（与其他卡片一致），组件不重复渲染。
 */
Component({
  props: {
    card: {}, // { medications: [MedicationView] }
  },
})
