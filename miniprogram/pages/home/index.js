const { ensureLogin } = require('../../utils/auth')
const { listProfiles, activateProfile } = require('../../services/health-profiles')
const { listAppointments, payAppointment } = require('../../services/appointments')
const { listDrugOrders } = require('../../services/drug-orders')
const { loadRegistrationSummary } = require('../../services/registration')
const { listConsultationProgress } = require('../../services/consultation')
const { listMessages } = require('../../services/patient-care')
const { readInAppMessageIds } = require('../../utils/messages')
const { hasLocation, isSkipped, markSkipped, chooseLocation } = require('../../utils/location')
const {
  decorateAppointment,
  remainingPaymentSeconds,
  formatCountdown,
} = require('../../utils/appointment')
const { STATUSES: ORDER_STATUSES } = require('../../utils/drug-order')

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

const CONSULTATION_PRIORITY = {
  IN_PROGRESS: 1,
  PENDING_CONFIRM: 2,
  WAITING_DOCTOR: 3,
  COLLECTING: 4,
}

// 处方追踪卡（票 86）：服务端只投影"问诊已完成且处方未终结"的最近链路；
// APPROVED 下单后即不再返回，交接给药品待支付卡。
const PRESCRIPTION_TODO = {
  PENDING: { badge: '处方审核中', badgeClass: 'todo-badge-warn', meta: '问诊已完成，处方审核中' },
  APPROVED: { badge: '处方已通过', badgeClass: '', meta: '处方已通过，去购药 ›' },
  REJECTED: { badge: '处方未通过', badgeClass: 'todo-badge-muted', meta: '处方未通过，点击查看详情 ›' },
}

/** 待办横卡：在线问诊进度优先，其后为处方追踪、待支付挂号、即将就诊/就诊中与药品订单（待支付 + 待取药/配送中履约跟进）。 */
function buildTodos(appointments, orders, consultationProgress) {
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
        priority: 6,
        updatedAt: item.payment_deadline || '',
        url: '/pages/appointments/index',
      }
    })
  // 即将就诊（BOOKED）与就诊中（IN_PROGRESS，票 86 叫号中间态）：叫号当天卡片无缝换文案；
  // VISITED 及之后为终态，客户端不再展示。
  const upcoming = decorated
    .filter((item) => (item.isBooked && item.schedule_date >= today) || item.isInProgress)
    .sort((a, b) => `${a.schedule_date} ${a.time_slot}`.localeCompare(`${b.schedule_date} ${b.time_slot}`))
    .map((item) => {
      const inProgress = item.isInProgress
      return {
        key: `appointment-${item.appointment_id}`,
        kind: 'appointment',
        badge: inProgress ? '就诊中' : '即将就诊',
        badgeClass: inProgress ? 'todo-badge-danger' : '',
        title: `${item.department_name} · ${item.doctor_name}医生`,
        meta: `${item.schedule_date} ${item.time_slot} · 第 ${item.sequence_number} 号`,
        hint: inProgress ? '医生已叫号，请前往诊室就诊' : '',
        payment_payable: false,
        priority: 7,
        updatedAt: `${item.schedule_date} ${item.time_slot}`,
        url: '/pages/appointments/index',
      }
    })
  const unpaid = (orders || [])
    .filter((item) => item.status === 'UNPAID')
    .map((item) => ({
      key: `order-${item.id}`,
      kind: 'order',
      badge: '待支付',
      title: `药品订单 #${item.id}`,
      meta: `合计 ¥${item.total_amount}`,
      priority: 8,
      updatedAt: item.created_at || '',
      url: '/pages/drug-orders/index',
    }))
  // 履约跟进：自取「待取药」轮到患者行动（与待支付同级优先），配送「配送中」为安心信息卡；
  // 已支付/调剂中是药师推进的过渡态不上首页，终态（已送达/已取药/已取消/已过期）自然消失。
  // badge 直接用 API 下发的 status_label，端侧不镜像 status_labels（utils/drug-order.js 约定）。
  const FULFILLMENT_TODO = {
    [ORDER_STATUSES.ready_for_pickup]: {
      priority: 8,
      meta: (item) => `药品已备好，凭订单到${item.campus_name || ''}${item.pharmacy_name || '药房'}取药 ›`,
    },
    [ORDER_STATUSES.shipped]: {
      priority: 9,
      meta: (item) => `${item.carrier_name || '配送'}已发出，请留意查收 ›`,
    },
  }
  const fulfillment = (orders || [])
    .filter((item) => FULFILLMENT_TODO[item.status])
    .map((item) => ({
      key: `order-${item.id}`,
      kind: 'order',
      badge: item.status_label,
      title: `药品订单 #${item.id}`,
      meta: FULFILLMENT_TODO[item.status].meta(item),
      priority: FULFILLMENT_TODO[item.status].priority,
      updatedAt: item.created_at || '',
      url: '/pages/drug-orders/index',
    }))
  const consultation = (consultationProgress || []).map((item) => {
    if (item.reference_type === 'PRESCRIPTION') {
      const deco = PRESCRIPTION_TODO[item.status] || {
        badge: item.status_label,
        badgeClass: '',
        meta: '',
      }
      return {
        key: `consultation-PRESCRIPTION-${item.reference_id}`,
        kind: 'consultation',
        badge: deco.badge,
        badgeClass: deco.badgeClass,
        title: `${item.department_name} · ${item.doctor_name}`,
        meta: deco.meta,
        priority: 5,
        updatedAt: item.updated_at || '',
        url: '/pages/prescriptions/index',
      }
    }
    const isDraft = item.reference_type === 'DRAFT'
    const url = isDraft
      ? item.status === 'PENDING_CONFIRM'
        ? `/pages/consult/summary/index?draftId=${item.reference_id}`
        : `/pages/consult/preconsult/index?draftId=${item.reference_id}&profileName=${encodeURIComponent(item.health_profile_name)}`
      : item.status === 'IN_PROGRESS'
        ? `/pages/consult/doctor/index?id=${item.reference_id}`
        : `/pages/consult/waiting/index?id=${item.reference_id}`
    return {
      key: `consultation-${item.reference_type}-${item.reference_id}`,
      kind: 'consultation',
      badge: item.status_label,
      title: `${item.health_profile_name}的在线问诊`,
      meta: '点击继续当前流程',
      priority: CONSULTATION_PRIORITY[item.status] || 4,
      updatedAt: item.updated_at || '',
      url,
    }
  })
  return [...consultation, ...pendingPayments, ...upcoming, ...unpaid, ...fulfillment].sort(
    (a, b) => a.priority - b.priority || b.updatedAt.localeCompare(a.updatedAt)
  )
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
    // 消息中心未读角标（票 93）：与消息中心页共用本机已读口径，仅 onShow 随 load 拉取
    unreadCount: 0,
    showEntrance: true,
    // AI挂号助手精简主卡：当前城市 + 平台医院真实总数
    regCityName: '',
    regTotal: 0,
  },

  onShow() {
    this.clearTodoCountdown()
    this.load()
    clearInterval(this._consultationTodoTimer)
    this._consultationTodoTimer = setInterval(() => this.refreshConsultationTodos(), 10000)
    this.loadRegistrationCard()
    this.promptLocationOnce()
  },

  onHide() {
    this.clearTodoCountdown()
    clearInterval(this._consultationTodoTimer)
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
    clearInterval(this._consultationTodoTimer)
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
          listConsultationProgress()
            .then((res) => (res && res.items) || [])
            .catch(() => this._consultationProgress || []),
          // 未读角标失败降级为不显示，不阻塞首页
          listMessages().catch(() => []),
        ])
      )
      .then(([profiles, appointments, orders, consultationProgress, messages]) => {
        const readIds = readInAppMessageIds()
        profiles = profiles.map((item) => ({ ...item, initial: item.display_name.slice(0, 1) }))
        const activeProfile = profiles.find((item) => item.active) || null
        this._appointments = appointments
        this._orders = orders
        this._consultationProgress = consultationProgress
        this.setData({
          profiles,
          activeProfile,
          profileLoaded: true,
          todos: buildTodos(appointments, orders, consultationProgress),
          unreadCount: messages.filter((item) => !readIds.includes(String(item.id))).length,
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
    const item = this.data.todos.find((todo) => todo.key === e.currentTarget.dataset.key)
    if (item && item.url) my.navigateTo({ url: item.url })
  },

  openMessages() {
    my.navigateTo({ url: '/pages/messages/index' })
  },

  refreshConsultationTodos() {
    return listConsultationProgress()
      .then((res) => {
        this._consultationProgress = (res && res.items) || []
        this.setData({
          todos: buildTodos(this._appointments || [], this._orders || [], this._consultationProgress),
        })
      })
      .catch(() => {})
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
