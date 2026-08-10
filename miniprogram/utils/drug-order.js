// 票 88（ADR-0035）：药品订单状态机/取药方式/履约动作常量。
// 与 contracts/order-flow.json 的 statuses/pickup_methods/decisions 对齐。
// 端侧无法读契约 JSON，此文件是 miniprogram 侧的本地镜像；契约变更须同步更新。
// 展示文案直接使用 API 下发的 status_label，端侧不镜像 status_labels。

// 九值状态机：配送 UNPAID -> PAID -> DISPENSING -> SHIPPED -> DELIVERED，
// 自取 UNPAID -> PAID -> DISPENSING -> READY_FOR_PICKUP -> PICKED_UP；
// 仅 UNPAID 可取消（CANCELLED），创建后 10 分钟未支付惰性过期（EXPIRED）
const STATUSES = {
  unpaid: 'UNPAID',
  paid: 'PAID',
  dispensing: 'DISPENSING',
  shipped: 'SHIPPED',
  delivered: 'DELIVERED',
  ready_for_pickup: 'READY_FOR_PICKUP',
  picked_up: 'PICKED_UP',
  cancelled: 'CANCELLED',
  expired: 'EXPIRED',
}

// 取药方式：所有院区药房同时支持院区自取与配送到家
const PICKUP_METHODS = {
  pickup: 'PICKUP',
  delivery: 'DELIVERY',
}

// C 端待支付动作（pay/cancel）与 B 端模拟履约推进动作（dispense/ship/deliver/ready/pickup）。
// 注意 pickup（取药动作）与 PICKUP_METHODS.pickup（取药方式）同值不同义
const DECISIONS = {
  pay: 'PAY',
  cancel: 'CANCEL',
  dispense: 'DISPENSE',
  ship: 'SHIP',
  deliver: 'DELIVER',
  ready: 'READY',
  pickup: 'PICKUP',
}

// 待支付时限（秒）：创建后 10 分钟未支付惰性过期
const PAYMENT_TIMEOUT_SECONDS = 600

// 虚构承运方（模拟配送，不接入真实物流）
const SIMULATED_CARRIER_NAME = '智愈模拟配送'

// 订单来源（与 contracts/order-flow.json 的 sources 对齐）
const SOURCES = {
  prescription: 'PRESCRIPTION',
  otc: 'OTC',
}

// 来源/取药方式中文标签：镜像 contracts/order-flow.json 的 source_labels/pickup_method_labels
const SOURCE_LABELS = {
  PRESCRIPTION: '处方药',
  OTC: '非处方药',
}

const PICKUP_METHOD_LABELS = {
  PICKUP: '院区自取',
  DELIVERY: '配送到家',
}

// 待支付倒计时剩余秒数；非 UNPAID 或 deadline 缺失/非法时返回 null（倒计时不渲染）
function remainingPaymentSeconds(order) {
  if (order.status !== STATUSES.unpaid || !order.payment_deadline) return null
  const deadlineMs = new Date(order.payment_deadline).getTime()
  if (Number.isNaN(deadlineMs)) return null
  return Math.max(0, Math.ceil((deadlineMs - Date.now()) / 1000))
}

// 倒计时 mm:ss（与 utils/appointment.js formatCountdown 同形）
function formatCountdown(seconds) {
  if (seconds == null) return ''
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${('0' + m).slice(-2)}:${('0' + s).slice(-2)}`
}

// OTC 候选药房距离格式化：不足 1 公里按米，否则保留一位小数的公里；无定位（null）返回空串
function formatDistance(meters) {
  if (meters == null) return ''
  if (meters < 1000) return `${Math.round(meters)}m`
  return `${(meters / 1000).toFixed(1)}km`
}

module.exports = {
  STATUSES,
  PICKUP_METHODS,
  DECISIONS,
  PAYMENT_TIMEOUT_SECONDS,
  SIMULATED_CARRIER_NAME,
  SOURCES,
  SOURCE_LABELS,
  PICKUP_METHOD_LABELS,
  remainingPaymentSeconds,
  formatCountdown,
  formatDistance,
}
