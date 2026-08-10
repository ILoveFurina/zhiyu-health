const { ensureLogin } = require('../../utils/auth')
const {
  getDrugOrder,
  payDrugOrder,
  cancelDrugOrder,
} = require('../../services/drug-orders')
const {
  STATUSES,
  SOURCES,
  SOURCE_LABELS,
  PICKUP_METHODS,
  PICKUP_METHOD_LABELS,
  remainingPaymentSeconds,
  formatCountdown,
} = require('../../utils/drug-order')

/** 药品订单详情（票 88）：价格快照/履约时间线/虚构承运物流/脱敏收货信息/自取地址。 */
Page({
  data: {
    loading: true,
    order: null,
    statusLabel: '',
    sourceLabel: '',
    pickupLabel: '',
    isDelivery: false,
    // 仅 UNPAID 展示支付/取消；倒计时到点本地置已过期事实（服务端惰性收敛，不主动重拉）
    isUnpaid: false,
    locallyExpired: false,
    countdownText: '',
    // 处方药订单交付后（已送达/已取药）展示用药提醒入口；OTC 不展示
    showReminderEntry: false,
    paying: false,
    cancelling: false,
  },

  onLoad(query) {
    this.orderId = query && query.id
  },

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
    return getDrugOrder(this.orderId)
      .then((order) => this.applyOrder(order))
      .catch((err) => {
        my.showToast({ content: err.detail || '订单加载失败', type: 'fail' })
      })
      .finally(() => this.setData({ loading: false }))
  },

  applyOrder(order, locallyExpired) {
    const isUnpaid = order.status === STATUSES.unpaid
    // 倒计时已归零但服务端尚未惰性收敛时，本地直接展示已过期事实
    const expired = Boolean(locallyExpired) ||
      (isUnpaid && remainingPaymentSeconds(order) === 0)
    const delivered =
      order.status === STATUSES.delivered || order.status === STATUSES.picked_up
    this.setData({
      order,
      isUnpaid,
      locallyExpired: expired,
      countdownText: '',
      statusLabel: expired ? '已过期' : order.status_label || order.status,
      sourceLabel: SOURCE_LABELS[order.source] || '',
      pickupLabel: PICKUP_METHOD_LABELS[order.pickup_method] || '',
      isDelivery: order.pickup_method === PICKUP_METHODS.delivery,
      showReminderEntry: delivered && order.source === SOURCES.prescription,
    })
    if (isUnpaid && !expired) this.startCountdown()
  },

  startCountdown() {
    this.clearCountdown()
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
    const order = this.data.order
    if (!order) return
    const seconds = remainingPaymentSeconds(order)
    if (seconds === null) {
      this.clearCountdown()
      return
    }
    if (seconds <= 0) {
      this.clearCountdown()
      // 到点本地置已过期事实：状态标签/操作按钮即时收敛，服务端下次读取惰性落 EXPIRED
      this.setData({ locallyExpired: true, statusLabel: '已过期', countdownText: '' })
      return
    }
    this.setData({ countdownText: formatCountdown(seconds) })
  },

  pay() {
    if (this.data.paying || this.data.cancelling) return
    my.confirm({
      title: '模拟支付',
      content: '这是演示支付，不会产生真实扣款。确认支付该订单吗？',
      success: (result) => {
        if (!result.confirm) return
        this.setData({ paying: true })
        payDrugOrder(this.orderId)
          .then(() => {
            my.showToast({ content: '模拟支付成功', type: 'success' })
            this.load()
          })
          .catch((err) => {
            my.showToast({ content: err.detail || '支付失败，请刷新后重试', type: 'fail' })
            this.load()
          })
          .finally(() => this.setData({ paying: false }))
      },
    })
  },

  cancel() {
    if (this.data.paying || this.data.cancelling) return
    my.confirm({
      title: '取消订单',
      content: '确认取消该待支付订单吗？库存将自动返还。',
      success: (result) => {
        if (!result.confirm) return
        this.setData({ cancelling: true })
        cancelDrugOrder(this.orderId)
          .then(() => {
            my.showToast({ content: '订单已取消', type: 'success' })
            this.load()
          })
          .catch((err) => {
            my.showToast({ content: err.detail || '取消失败，请刷新后重试', type: 'fail' })
            this.load()
          })
          .finally(() => this.setData({ cancelling: false }))
      },
    })
  },

  /** 用药提醒入口：处方药交付后由 server-java 幂等生成服药打卡，跳站内消息通道查看。 */
  openReminder() {
    my.navigateTo({ url: '/pages/messages/index' })
  },
})
