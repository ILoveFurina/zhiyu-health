// 票 44：情绪反馈映射。与 contracts/emotion.json 的 emotions/soothing_texts 对齐。
// 端侧无法读契约 JSON，此文件是 miniprogram 侧的本地镜像；契约变更须同步更新。
const EMOTIONS = ['calm', 'anxious', 'fearful']
const DEFAULT_EMOTION = 'calm'

// calm 无安抚语；anxious/fearful 各一条确定性文案，与 contracts/emotion.json 一致。
const SOOTHING_TEXTS = {
  anxious: '别太担心，我会一步步陪你看懂接下来怎么做。',
  fearful: '这听起来确实让人紧张，建议尽快联系医生或拨打 120。',
}

/** 取安抚语：calm 返回空串（无安抚语），其余按映射取，未知 emotion 回退空串。 */
function soothingTextFor(emotion) {
  if (!emotion || emotion === 'calm') return ''
  return SOOTHING_TEXTS[emotion] || ''
}

module.exports = { EMOTIONS, DEFAULT_EMOTION, SOOTHING_TEXTS, soothingTextFor }
