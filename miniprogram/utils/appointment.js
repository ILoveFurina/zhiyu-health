// contracts/appointment-flow.json 的支付宝小程序手工镜像（票 71）。
const STATUSES = Object.freeze({
  booked: 'BOOKED',
  inProgress: 'IN_PROGRESS',
  cancelled: 'CANCELLED',
  visited: 'VISITED',
})

const STATUS_LABELS = Object.freeze({
  BOOKED: '已约',
  IN_PROGRESS: '就诊中',
  CANCELLED: '已取消',
  VISITED: '已接诊',
})

const MESSAGE_TYPES = Object.freeze({ called: 'appointment_called' })

function decorateAppointment(item) {
  return {
    ...item,
    status: STATUS_LABELS[item.status_code] || item.status,
    isBooked: item.status_code === STATUSES.booked,
    isInProgress: item.status_code === STATUSES.inProgress,
    hasVoucher: item.status_code !== STATUSES.cancelled,
  }
}

module.exports = { STATUSES, STATUS_LABELS, MESSAGE_TYPES, decorateAppointment }
