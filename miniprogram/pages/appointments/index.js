const { ensureLogin } = require('../../utils/auth')
const { listAppointments, cancelAppointment, payAppointment } = require('../../services/appointments')
const { currentProfile } = require('../../services/health-profiles')
const {
  decorateAppointment,
  remainingPaymentSeconds,
  formatCountdown,
} = require('../../utils/appointment')

const FILTER_TABS = [
  { key: 'all', label: '全部' },
  { key: 'unpaid', label: '待支付' },
  { key: 'upcoming', label: '待就诊' },
  { key: 'history', label: '历史' },
]

function filterAppointments(appointments, key) {
  if (key === 'unpaid') return appointments.filter((item) => item.payment_payable)
  if (key === 'upcoming') return appointments.filter((item) => item.isBooked || item.isInProgress)
  if (key === 'history') {
    return appointments.filter((item) => !item.payment_payable && !item.isBooked && !item.isInProgress)
  }
  return appointments
}

Page({
  data: {
    loading: true,
    allAppointments: [],
    appointments: [],
    currentProfile: null,
    filterTabs: FILTER_TABS,
    activeFilter: 'all',
  },

  onShow() {
    ensureLogin().then(() => this.loadAppointments())
  },

  onHide() {
    this.clearCountdown()
  },

  onUnload() {
    this.clearCountdown()
  },

  loadAppointments() {
    this._countdownTriggered = false
    this.clearCountdown()
    this.setData({ loading: true })
    return Promise.all([listAppointments(), currentProfile()])
      .then(([appointments, profileResult]) => {
        const decorated = appointments.map(decorateAppointment)
        this.setData({
          allAppointments: decorated,
          appointments: filterAppointments(decorated, this.data.activeFilter),
          currentProfile: profileResult.profile,
        })
        this.startCountdown()
      })
      .catch(() => my.showToast({ content: '挂号记录加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },

  startCountdown() {
    this.clearCountdown()
    const hasPending = this.data.allAppointments.some(
      (item) => item.isPendingPayment && item.payment_deadline
    )
    if (!hasPending) return
    this.updateCountdown()
    this._countdownTimer = setInterval(() => this.updateCountdown(), 1000)
  },

  clearCountdown() {
    if (this._countdownTimer) {
      clearInterval(this._countdownTimer)
      this._countdownTimer = null
    }
  },

  updateCountdown() {
    let shouldReload = false
    const decorate = (item) => {
      if (!item.isPendingPayment || !item.payment_deadline) return item
      const seconds = remainingPaymentSeconds(item)
      if (seconds === null) return item
      if (seconds <= 0) {
        shouldReload = true
        return { ...item, paymentCountdownText: '00:00', paymentExpired: true, payment_payable: false }
      }
      return { ...item, paymentCountdownText: formatCountdown(seconds) }
    }
    const allAppointments = this.data.allAppointments.map(decorate)
    this.setData({
      allAppointments,
      appointments: filterAppointments(allAppointments, this.data.activeFilter),
    })
    if (shouldReload && !this._countdownTriggered) {
      this._countdownTriggered = true
      this.loadAppointments()
    }
  },

  onFilterTap(e) {
    const activeFilter = e.currentTarget.dataset.key
    this.setData({
      activeFilter,
      appointments: filterAppointments(this.data.allAppointments, activeFilter),
    })
  },

  cancel(e) {
    const appointmentId = e.currentTarget.dataset.id
    my.confirm({
      title: '取消挂号',
      content: '确认取消这次挂号吗？号源将自动返还。',
      success: (result) => {
        if (!result.confirm) return
        cancelAppointment(appointmentId)
          .then(() => {
            my.showToast({ content: '已取消', type: 'success' })
            this.loadAppointments()
          })
          .catch(() => my.showToast({ content: '取消失败，请稍后重试', type: 'fail' }))
      },
    })
  },
  pay(e) {
    const appointmentId = e.currentTarget.dataset.id
    my.confirm({
      title: '模拟支付',
      content: '这是演示支付，不会产生真实扣款。确认支付诊查费吗？',
      success: (result) => {
        if (!result.confirm) return
        payAppointment(appointmentId)
          .then(() => {
            my.showToast({ content: '支付成功', type: 'success' })
            this.loadAppointments()
          })
          .catch(() => my.showToast({ content: '支付失败，请稍后重试', type: 'fail' }))
      },
    })
  },
  openPrescriptions() { my.navigateTo({ url: '/pages/prescriptions/index' }) },
  goBooking() { my.navigateTo({ url: '/pages/booking/standard-departments/index' }) },
  openDrugOrders() { my.navigateTo({ url: '/pages/drug-orders/index' }) },
  openMessages() { my.navigateTo({ url: '/pages/messages/index' }) },
  openHealthProfiles() { my.navigateTo({ url: '/pages/health/index' }) },
})
