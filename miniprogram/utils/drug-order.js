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

module.exports = { STATUSES, PICKUP_METHODS, DECISIONS, PAYMENT_TIMEOUT_SECONDS, SIMULATED_CARRIER_NAME }
