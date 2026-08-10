const { ensureLogin } = require('../../utils/auth')
const { listDrugOrders, cancelDrugOrder, payDrugOrder } = require('../../services/drug-orders')
const {
  STATUSES,
  SOURCE_LABELS,
  PICKUP_METHOD_LABELS,
  remainingPaymentSeconds,
  formatCountdown,
} = require('../../utils/drug-order')

/** 药品订单列表（票 88）：状态/金额/药房名；UNPAID 展示 10 分钟支付倒计时与模拟支付/取消。 */
Page({
  data: { loading: true, orders: [], cancellingId: null, payingId: null },

  onShow() {
    ensureLogin().then(() => this.load())
  },

  onHide() {
    this.clearCountdown()
  },

  onUnload() {
    this.clearCountdown()
  },

  load() {
    this.clearCountdown()
    this.setData({ loading: true })
    listDrugOrders()
      .then((orders) => {
        this.setData({ orders: orders.map((order) => this.decorate(order)) })
        this.startCountdown()
      })
      .catch(() => my.showToast({ content: '药品订单加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },

  /** 状态文案用 API 下发的 status_label；来源/取药方式标签取契约镜像；倒计时按 payment_deadline 现算。 */
  decorate(order, locallyExpired) {
    const isUnpaid = order.status === STATUSES.unpaid
    const expired = Boolean(locallyExpired) ||
      (isUnpaid && remainingPaymentSeconds(order) === 0)
    return {
      ...order,
      locallyExpired: expired,
      statusLabel: expired ? '已过期' : order.status_label || order.status,
      sourceLabel: SOURCE_LABELS[order.source] || '',
      pickupLabel: PICKUP_METHOD_LABELS[order.pickup_method] || '',
      canOperate: isUnpaid && !expired,
      countdownText: '',
    }
  },

  startCountdown() {
    this.clearCountdown()
    const hasUnpaid = this.data.orders.some((item) => item.canOperate && item.payment_deadline)
    if (!hasUnpaid) return
    this.tickCountdown()
    this._countdownTimer = setInterval(() => this.tickCountdown(), 1000)
  },

  clearCountdown() {
    if (this._countdownTimer) {
      clearInterval(this._countdownTimer)
      this._countdownTimer = null
    }
  },

  tickCountdown() {
    let allSettled = true
    const orders = this.data.orders.map((item) => {
      if (!item.canOperate) return item
      const seconds = remainingPaymentSeconds(item)
      if (seconds === null) return item
      if (seconds <= 0) {
        // 到点本地刷新为已过期事实（服务端读取入口惰性收敛，不主动重拉）
        return this.decorate(item, true)
      }
      allSettled = false
      return { ...item, countdownText: formatCountdown(seconds) }
    })
    this.setData({ orders })
    if (allSettled) this.clearCountdown()
  },

  openDetail(e) {
    const id = e.currentTarget.dataset.id
    my.navigateTo({ url: `/pages/drug-order-detail/index?id=${id}` })
  },

  pay(e) {
    const id = e.currentTarget.dataset.id
    this.setData({ payingId: id })
    payDrugOrder(id)
      .then(() => {
        my.showToast({ content: '模拟支付成功', type: 'success' })
        this.load()
      })
      .catch((err) => {
        my.showToast({ content: err.detail || '支付失败，请刷新后重试', type: 'fail' })
        this.load()
      })
      .finally(() => this.setData({ payingId: null }))
  },

  cancel(e) {
    const id = e.currentTarget.dataset.id
    my.confirm({
      title: '取消订单',
      content: '确认取消该待支付订单吗？库存将自动返还。',
      success: (result) => {
        if (!result.confirm) return
        this.setData({ cancellingId: id })
        cancelDrugOrder(id)
          .then(() => {
            my.showToast({ content: '订单已取消', type: 'success' })
            this.load()
          })
          .catch((err) => {
            my.showToast({ content: err.detail || '取消失败，请刷新后重试', type: 'fail' })
            this.load()
          })
          .finally(() => this.setData({ cancellingId: null }))
      },
    })
  },
})
