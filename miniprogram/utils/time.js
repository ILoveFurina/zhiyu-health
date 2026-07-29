/**
 * 相对时间格式化（决策 11：相对时间归前端，server-java 不碰中文文案）。
 * 接受 ISO 时间戳，返回"刚刚 / N 分钟前 / N 小时前 / 昨天 / M月D日 / 去年M月D日"。
 */
function formatRelativeTime(iso) {
  if (!iso) return ''
  const ts = new Date(iso).getTime()
  if (Number.isNaN(ts)) return ''
  const now = Date.now()
  const diff = now - ts
  if (diff < 60 * 1000) return '刚刚'
  if (diff < 60 * 60 * 1000) return `${Math.floor(diff / (60 * 1000))} 分钟前`
  if (diff < 24 * 60 * 60 * 1000) return `${Math.floor(diff / (60 * 60 * 1000))} 小时前`
  const target = new Date(ts)
  const today = new Date(now)
  const sameDate =
    target.getFullYear() === today.getFullYear() &&
    target.getMonth() === today.getMonth() &&
    target.getDate() === today.getDate()
  if (sameDate) return '刚刚'
  const yesterday = new Date(today)
  yesterday.setDate(today.getDate() - 1)
  const isYesterday =
    target.getFullYear() === yesterday.getFullYear() &&
    target.getMonth() === yesterday.getMonth() &&
    target.getDate() === yesterday.getDate()
  if (isYesterday) return '昨天'
  if (target.getFullYear() === today.getFullYear()) {
    return `${target.getMonth() + 1}月${target.getDate()}日`
  }
  return `去年${target.getMonth() + 1}月${target.getDate()}日`
}

module.exports = { formatRelativeTime }
