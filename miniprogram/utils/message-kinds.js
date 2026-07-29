// 结构化卡片消息种类：AI 产出的非文本消息，与 server-java Message.KIND_* 对齐。
// 历史回放时需把这类消息的 JSON content 还原为 card 对象渲染。
const CARD_KINDS = [
  'doctor_recommendations',
  'doctor_slots',
  'hospital_recommendations',
  'appointment',
  'appointments',
]

function isCardKind(kind) {
  return CARD_KINDS.indexOf(kind) !== -1
}

module.exports = { CARD_KINDS, isCardKind }
