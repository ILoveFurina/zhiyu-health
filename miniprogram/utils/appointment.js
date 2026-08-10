// contracts/appointment-flow.json 的支付宝小程序手工镜像（票 71，票 81 支付门控）。
const STATUSES = Object.freeze({
  pendingPayment: 'PENDING_PAYMENT',
  booked: 'BOOKED',
  inProgress: 'IN_PROGRESS',
  cancelled: 'CANCELLED',
  visited: 'VISITED',
})

const STATUS_LABELS = Object.freeze({
  PENDING_PAYMENT: '待支付',
  BOOKED: '待就诊',
  IN_PROGRESS: '就诊中',
  CANCELLED: '已取消',
  VISITED: '已接诊',
})

const MESSAGE_TYPES = Object.freeze({ called: 'appointment_called' })

function decorateAppointment(item) {
  return {
    ...item,
    status: STATUS_LABELS[item.status_code] || item.status,
    isPendingPayment: item.status_code === STATUSES.pendingPayment,
    isBooked: item.status_code === STATUSES.booked,
    isInProgress: item.status_code === STATUSES.inProgress,
    isCancelled: item.status_code === STATUSES.cancelled,
    // 挂号凭证（就诊序号）仅在已支付后展示：待支付尚不构成有效就诊凭证（票 81）。
    hasVoucher: item.status_code !== STATUSES.cancelled && item.status_code !== STATUSES.pendingPayment,
    // 可取消标记由后端计算下发（票 89）：待支付未过期或已支付未过取消截止时刻才可取消，
    // 前端不自行复制时段表（ADR-0034 cancellable 同构 callable 模式）。
    isCancellable: item.cancellable === true,
    // 已退款标记：取消已支付预约后 payment_status 为 REFUNDED，用于展示退款文案。
    isRefunded: item.payment_status === 'REFUNDED',
  }
}

function remainingPaymentSeconds(item) {
  if (item.status_code !== STATUSES.pendingPayment || !item.payment_deadline) return null
  const deadlineMs = new Date(item.payment_deadline).getTime()
  if (Number.isNaN(deadlineMs)) return null
  return Math.max(0, Math.ceil((deadlineMs - Date.now()) / 1000))
}

function formatCountdown(seconds) {
  if (seconds == null) return ''
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${('0' + m).slice(-2)}:${('0' + s).slice(-2)}`
}

module.exports = {
  STATUSES,
  STATUS_LABELS,
  MESSAGE_TYPES,
  decorateAppointment,
  remainingPaymentSeconds,
  formatCountdown,
}
