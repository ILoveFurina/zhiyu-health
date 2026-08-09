/**
 * 购药确认卡（票 79）：server-py 经 prepare_drug_order 工具装配后以 drug_order_confirm
 * 事件下发，展示药品明细/单价/库存/总价/处方来源，待用户在卡片上确认下单，不扣库存不建单。
 *
 * 确认卡是下单唯一入口（硬边界）：Agent 不直接扣库存，用户点「确认下单」后由 C 端直接调
 * POST /api/c/drug-orders（OTC: prescription_id=null + items；处方药: prescription_id + items），
 * 下单成功后确认卡就地转结果态（或追加 drug_order 结果卡），失败提示库存不足等。
 *
 * content JSON 字段（与 MedicationToolService.PrepareOrderView 对齐）：
 *   source(PRESCRIPTION|OTC 枚举值,见 contracts/order-flow.json sources)/
 *   prescription_id/items[{medication_id,name,specification,quantity,unit_price,
 *   subtotal,stock,available}]/total_amount/payable_amount/
 *   prescription_source_type/doctor_name/prescription_date
 */
Component({
  props: {
    card: {}, // PrepareOrderView
    cardId: null, // 宿主消息 id，回调原样带回供宿主定位
    submitting: false, // 是否下单中（宿主控制，禁用按钮）
    submitted: false, // 是否已下单成功（就地转结果态，隐藏确认/取消按钮）
    onConfirm: () => {},
    onCancel: () => {},
  },

  data: {
    // 处方来源文案：处方药路径展示「医生姓名 · 日期」，OTC 路径为空
    prescriptionSourceText: '',
  },

  deriveDataFromProps(nextProps) {
    const card = nextProps.card || {}
    let prescriptionSourceText = ''
    // source 取契约枚举值（PRESCRIPTION/OTC，见 contracts/order-flow.json sources），
    // 非小写键名；处方药路径展示「医生姓名 · 日期」，OTC 路径为空
    if (card.source === 'PRESCRIPTION') {
      const doctor = card.doctor_name || ''
      const date = card.prescription_date || ''
      prescriptionSourceText = [doctor ? `${doctor}医生` : '', date].filter(Boolean).join(' · ')
    }
    this.setData({ prescriptionSourceText })
  },

  methods: {
    confirm() {
      if (this.props.submitting || this.props.submitted) return
      this.props.onConfirm({ cardId: this.props.cardId, card: this.props.card })
    },
    cancel() {
      if (this.props.submitting) return
      this.props.onCancel({ cardId: this.props.cardId })
    },
  },
})
