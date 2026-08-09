const { ensureLogin } = require('../../utils/auth')
const { listProfiles, activateProfile } = require('../../services/health-profiles')
const { listAppointments, payAppointment } = require('../../services/appointments')
const { listDrugOrders } = require('../../services/drug-orders')
const { loadRegistrationSummary } = require('../../services/registration')
const { hasLocation, isSkipped, markSkipped, chooseLocation } = require('../../utils/location')
const {
  decorateAppointment,
  remainingPaymentSeconds,
  formatCountdown,
} = require('../../utils/appointment')

// 启动就医位置确认只问一次（会话级标志）；跳过或确认后不再打扰
let locationPrompted = false

/**
 * 首页功能目录宫格（CONTEXT.md「功能目录」词条）：
 * 就医服务指向 tab/独立页面；报告解读入口页（pages/report）属票 42 阶段三。
 */
const GRIDS = [
  {
    title: '就医服务',
    columns: 2,
    items: [
      { key: 'triage', icon: 'plus', label: '智能导诊', desc: '描述症状，推荐科室', action: 'switchTab', url: '/pages/chat/index' },
      { key: 'consult', icon: 'consult', label: '在线问诊', desc: 'AI 预问诊，医生接诊', action: 'navigateTo', url: '/pages/consult/entry/index' },
      { key: 'booking', icon: 'calendar', label: '预约挂号', desc: '选科室、医生与时间', action: 'navigateTo', url: '/pages/booking/standard-departments/index' },
      { key: 'report', icon: 'report', label: '报告解读', desc: '上传报告，AI 解读', action: 'navigateTo', url: '/pages/report/index' },
    ],
  },
  {
    title: '健康管理',
    columns: 4,
    items: [
      { key: 'health', icon: 'heart', label: '健康档案', action: 'navigateTo', url: '/pages/health/index' },
      { key: 'appointments', icon: 'ticket', label: '我的挂号', action: 'navigateTo', url: '/pages/appointments/index' },
      { key: 'prescriptions', icon: 'file', label: '电子处方', action: 'navigateTo', url: '/pages/prescriptions/index' },
      { key: 'drugOrders', icon: 'capsule', label: '药品订单', action: 'navigateTo', url: '/pages/drug-orders/index' },
    ],
  },
]

function greeting() {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
}

/** 本地当天 YYYY-MM-DD，用于与 schedule_date 字符串比较筛选未来就诊。 */
function todayString() {
  const now = new Date()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  const day = `${now.getDate()}`.padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

/** 待办横卡数据：待支付挂号、即将就诊挂号 + 待支付药品订单。 */
function buildTodos(appointments, orders) {
  const today = todayString()
  const decorated = (appointments || []).map(decorateAppointment)
  const pendingPayments = decorated
    .filter((item) => item.isPendingPayment)
    .map((item) => {
      const seconds = remainingPaymentSeconds(item)
      return {
        key: `appointment-${item.appointment_id}`,
        kind: 'appointment',
        badge: '待支付',
        title: `${item.department_name} · ${item.doctor_name}医生`,
        meta: `${item.schedule_date} ${item.time_slot} · 待支付诊查费 ¥${item.registration_fee || '--'}`,
        payment_payable: true,
        appointmentId: item.appointment_id,
        paymentDeadline: item.payment_deadline,
        paymentCountdownText: seconds == null ? '' : formatCountdown(seconds),
        paymentExpired: seconds != null && seconds <= 0,
      }
    })
  const upcoming = decorated
    .filter((item) => item.isBooked && item.schedule_date >= today)
    .sort((a, b) => `${a.schedule_date} ${a.time_slot}`.localeCompare(`${b.schedule_date} ${b.time_slot}`))
    .map((item) => ({
      key: `appointment-${item.appointment_id}`,
      kind: 'appointment',
      badge: '即将就诊',
      title: `${item.department_name} · ${item.doctor_name}医生`,
      meta: `${item.schedule_date} ${item.time_slot} · 第 ${item.sequence_number} 号`,
      payment_payable: false,
    }))
  const unpaid = (orders || [])
    .filter((item) => item.status === 'UNPAID')
    .map((item) => ({
      key: `order-${item.id}`,
      kind: 'order',
      badge: '待支付',
      title: `药品订单 #${item.id}`,
      meta: `合计 ¥${item.total_amount}`,
    }))
  return [...pendingPayments, ...upcoming, ...unpaid]
}

Page({
  data: {
    greeting: greeting(),
    grids: GRIDS,
    profileLoaded: false,
    profiles: [],
    activeProfile: null,
    sheetOpen: false,
    todos: [],
    showEntrance: true,
    // AI挂号助手精简主卡：当前城市 + 平台医院真实总数
    regCityName: '',
    regTotal: 0,
  },

  onShow() {
    this.clearTodoCountdown()
    this.load()
    this.loadRegistrationCard()
    this.promptLocationOnce()
  },

  onHide() {
    this.clearTodoCountdown()
  },

  /**
   * 启动就医位置确认（可跳过，票 49）：每会话首次 onShow 询问一次；
   * 取消仅标记 skipped（此后不展示距离），不阻塞任何其他功能。
   */
  promptLocationOnce() {
    if (locationPrompted) return
    locationPrompted = true
    if (hasLocation() || isSkipped()) return
    my.confirm({
      title: '确认就医位置',
      content: '是否确认就医位置，用于按距离展示同城医院',
      confirmButtonText: '确认位置',
      cancelButtonText: '跳过',
      success: (res) => {
        if (res.confirm) {
          chooseLocation().then((picked) => {
            if (picked) this.loadRegistrationCard()
          })
        } else {
          markSkipped()
        }
      },
    })
  },

  /** 主卡数据失败不阻塞问候头与宫格，降级为空主卡。 */
  loadRegistrationCard() {
    return loadRegistrationSummary()
      .then(({ cityName, total }) => this.setData({ regCityName: cityName, regTotal: total }))
      .catch(() => {})
  },

  onDepartmentEntry() {
    my.navigateTo({ url: '/pages/booking/standard-departments/index' })
  },

  /** 主卡「智能导诊」：经 globalData 交棒（switchTab 不能带参），chat 页 onShow 消费后自动进入导诊引导。 */
  onGuideEntry() {
    getApp().globalData.pendingTriageEntry = true
    my.switchTab({ url: '/pages/chat/index' })
  },

  onMoreHospitals() {
    my.navigateTo({ url: '/pages/booking/hospitals/index' })
  },

  onReady() {
    // 首页驻留期间只播放一次；切换 tab 再返回不会重触发。
    this._entranceTimer = setTimeout(() => this.setData({ showEntrance: false }), 560)
  },

  onUnload() {
    clearTimeout(this._entranceTimer)
    this.clearTodoCountdown()
  },

  /** 首页待支付挂号的支付倒计时：到期为本页收敛并重新拉数据，避免展示已过期卡。 */
  startTodoCountdown() {
    this.clearTodoCountdown()
    const hasPending = (this.data.todos || []).some((item) => item.payment_payable)
    if (!hasPending) return
    this.updateTodoCountdown()
    this._countdownTimer = setInterval(() => this.updateTodoCountdown(), 1000)
  },

  clearTodoCountdown() {
    if (this._countdownTimer) {
      clearInterval(this._countdownTimer)
      this._countdownTimer = null
    }
  },

  updateTodoCountdown() {
    let shouldReload = false
    const todos = (this.data.todos || []).map((item) => {
      if (!item.payment_payable) return item
      const seconds = remainingPaymentSeconds({
        status_code: 'PENDING_PAYMENT',
        payment_deadline: item.paymentDeadline,
      })
      if (seconds === null) return item
      if (seconds <= 0) {
        shouldReload = true
        return { ...item, paymentCountdownText: '00:00', paymentExpired: true, payment_payable: false }
      }
      return { ...item, paymentCountdownText: formatCountdown(seconds) }
    })
    this.setData({ todos })
    if (shouldReload && !this._countdownTriggered) {
      this._countdownTriggered = true
      this.load()
    }
  },

  payTodo(e) {
    const appointmentId = e.currentTarget.dataset.id
    my.confirm({
      title: '模拟支付',
      content: '这是演示支付，不会产生真实扣款。确认支付诊查费吗？',
      success: (result) => {
        if (!result.confirm) return
        payAppointment(appointmentId)
          .then(() => {
            my.showToast({ content: '支付成功', type: 'success' })
            this.load()
          })
          .catch(() => my.showToast({ content: '支付失败，请稍后重试', type: 'fail' }))
      },
    })
  },

  load() {
    this._countdownTriggered = false
    this.clearTodoCountdown()
    return ensureLogin()
      .then(() =>
        Promise.all([
          listProfiles(),
          // 待办数据失败不阻塞问候头与宫格，降级为空待办
          listAppointments().catch(() => []),
          listDrugOrders().catch(() => []),
        ])
      )
      .then(([profiles, appointments, orders]) => {
        profiles = profiles.map((item) => ({ ...item, initial: item.display_name.slice(0, 1) }))
        const activeProfile = profiles.find((item) => item.active) || null
        this.setData({
          profiles,
          activeProfile,
          profileLoaded: true,
          todos: buildTodos(appointments, orders),
        }, () => this.startTodoCountdown())
      })
      .catch(() => my.showToast({ content: '加载失败，请稍后重试', type: 'fail' }))
  },

  onGridTap(e) {
    const { key, action, url } = e.currentTarget.dataset
    // 宫格「智能导诊」与主卡入口同一路径：交棒 chat 页自动进入导诊引导
    if (key === 'triage') getApp().globalData.pendingTriageEntry = true
    if (action === 'switchTab') my.switchTab({ url })
    else my.navigateTo({ url })
  },

  openTodo(e) {
    const kind = e.currentTarget.dataset.kind
    my.navigateTo({ url: kind === 'order' ? '/pages/drug-orders/index' : '/pages/appointments/index' })
  },

  openSheet() {
    this.setData({ sheetOpen: true })
  },

  closeSheet() {
    this.setData({ sheetOpen: false })
  },

  noop() {},

  switchProfile(e) {
    const profileId = e.currentTarget.dataset.id
    if (this.data.activeProfile && profileId === this.data.activeProfile.id) {
      this.closeSheet()
      return
    }
    activateProfile(profileId)
      .then(() => {
        this.closeSheet()
        return this.load()
      })
      .catch(() => my.showToast({ content: '切换失败，请稍后重试', type: 'fail' }))
  },

  createProfile() {
    this.closeSheet()
    my.navigateTo({ url: '/pages/health/index?create=1' })
  },
})
