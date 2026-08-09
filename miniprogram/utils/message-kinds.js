// 结构化卡片消息种类：AI 产出的非文本消息，与 server-java Message.KIND_* 对齐。
// 历史回放时需把这类消息的 JSON content 还原为 card 对象渲染。
const CARD_KINDS = [
  'doctor_recommendations',
  'doctor_slots',
  'hospital_recommendations',
  'appointment',
  'appointments',
  'report_interpretation',
  'skin_analysis',
  'diet_analysis',
  'tongue_analysis',
  // 票 50：智能导诊科室明确后下发的跨医院 14 天号源卡（含 failed 状态）
  'department_slots',
  // 票 65：ambiguous 多科室候选时下发的科室选择卡（点选直查号源，可重复点）
  'department_options',
  // 票 75/77/78：购药相关卡片
  'prescriptions', // 多处方选择卡（票 78），点选回传 prescription_id
  'drug_order_prepare', // 购药确认卡（票 77，实时流事件名，与 drug_order_confirm 同组件渲染）
  'drug_order_confirm', // 购药确认卡（历史回放可能以此 kind 落库）
  'drug_order', // 购药结果卡
]

function isCardKind(kind) {
  return CARD_KINDS.indexOf(kind) !== -1
}

module.exports = { CARD_KINDS, isCardKind }
