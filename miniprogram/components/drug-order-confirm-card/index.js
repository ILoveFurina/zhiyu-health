/**
 * 购药预览卡（票 88，沿用 message kind=drug_order_confirm）：server-py 购药工具装配后下发，
 * 只展示非敏感稳定事实——来源、药品名称/规格/数量，处方药路径另含处方医生/日期/医院/院区/
 * 锁定院区药房。卡片不含收货人/电话/地址、取药方式、物流或最终价格库存承诺。
 *
 * 点击只跳统一购药确认页（/pages/drug-order-confirm），不在聊天内提交订单；
 * 价格与库存以确认页实时校验为准，历史会话回放同样可点击跳确认页实时校验。
 *
 * content JSON 字段（与 contracts/order-flow.json 的 drug_order_confirm 说明对齐）：
 *   source(PRESCRIPTION|OTC)/prescription_id(处方药时非空)/
 *   items[{medication_id,name,specification,quantity}]/
 *   doctor_name/prescription_date/hospital_name/campus_name/pharmacy_name(处方药路径)
 */
Component({
  props: {
    card: {},
    cardId: null, // 宿主消息 id，回调原样带回供宿主定位
    onOpen: () => {},
  },

  data: {
    // 处方来源文案：处方药路径展示「医生姓名 · 日期」，OTC 路径为空
    prescriptionSourceText: '',
  },

  deriveDataFromProps(nextProps) {
    const card = nextProps.card || {}
    let prescriptionSourceText = ''
    if (card.source === 'PRESCRIPTION') {
      const doctor = card.doctor_name || ''
      const date = card.prescription_date || ''
      prescriptionSourceText = [doctor ? `${doctor}医生` : '', date].filter(Boolean).join(' · ')
    }
    this.setData({ prescriptionSourceText })
  },

  methods: {
    open() {
      this.props.onOpen({ cardId: this.props.cardId, card: this.props.card })
    },
  },
})
