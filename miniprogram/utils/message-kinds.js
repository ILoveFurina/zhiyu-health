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
]

function isCardKind(kind) {
  return CARD_KINDS.indexOf(kind) !== -1
}

module.exports = { CARD_KINDS, isCardKind }
