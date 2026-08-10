// 站内消息已读态（票 96 抽取共享）：服务端无已读字段，视觉已读只存本机 storage。
// 首页未读角标与消息中心页共用同一份口径，两处各自实现会导致数字与已读态漂移。
const READ_MESSAGE_IDS_KEY = 'readInAppMessageIds'

function readInAppMessageIds() {
  const stored = my.getStorageSync({ key: READ_MESSAGE_IDS_KEY }).data
  return Array.isArray(stored) ? stored.map(String) : []
}

/** 标记已读并返回最新集合；幂等，重复标记不产生重复项。 */
function markInAppMessageRead(id) {
  const readIds = readInAppMessageIds()
  const key = String(id)
  if (readIds.includes(key)) return readIds
  const next = [...readIds, key]
  my.setStorageSync({ key: READ_MESSAGE_IDS_KEY, data: next })
  return next
}

module.exports = { readInAppMessageIds, markInAppMessageRead }
