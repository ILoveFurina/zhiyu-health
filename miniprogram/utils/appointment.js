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
    // 挂号凭证（就诊序号）仅在已支付后展示：待支付尚不构成有效就诊凭证（票 81）。
    hasVoucher: item.status_code !== STATUSES.cancelled && item.status_code !== STATUSES.pendingPayment,
  }
}

module.exports = { STATUSES, STATUS_LABELS, MESSAGE_TYPES, decorateAppointment }
