/**
 * 处方选择卡（票 78）：list_approved_prescriptions 返回多张 APPROVED 处方时由 server-py
 * 经 prescriptions 事件下发，端侧渲染为可点选的处方列表，每项展示处方来源（开方医生+日期+
 * 来源类型）与药品摘要（药名/规格），用户点选某处方后回传 prescription_id 触发
 * prepare_drug_order -> drug_order_prepare 确认卡（与 77 单处方直通共用后半段）。
 *
 * 复用 department_options 的「点选回传」形态：点选不跳页，由宿主以「按此处方买药」文案 +
 * prescription_id 可选字段发起对话轮（见 chat-stream.js requestData / index.js startRound）。
 *
 * content JSON 字段（与 server-java MedicationToolService.PrescriptionCardView 对齐）：
 *   prescriptions[{prescription_id, doctor_name, source_type(APPOINTMENT|ONLINE_CONSULTATION),
 *   source_type_label(线下接诊|在线问诊), date, items[{medication_id, name, specification,
 *   dosage, frequency, duration}]}]
 */
Component({
  props: {
    card: {}, // { prescriptions: [PrescriptionCardView] }
    cardId: null, // 宿主消息 id，回调原样带回供宿主定位
    onSelect: () => {},
  },

  methods: {
    select(e) {
      const { id } = e.currentTarget.dataset
      if (!id) return
      this.props.onSelect({ cardId: this.props.cardId, prescriptionId: id })
    },
  },
})
