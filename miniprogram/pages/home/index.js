const { ensureLogin } = require('../../utils/auth')
const { listProfiles, activateProfile } = require('../../services/health-profiles')
const { listAppointments } = require('../../services/appointments')
const { listDrugOrders } = require('../../services/drug-orders')
const { loadRegistrationSummary } = require('../../services/registration')
const { hasLocation, isSkipped, markSkipped, chooseLocation, relocate } = require('../../utils/location')

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
      { key: 'triage', icon: '✚', label: '智能导诊', desc: '描述症状，推荐科室', action: 'switchTab', url: '/pages/chat/index' },
      { key: 'consult', icon: '⚕', label: '在线问诊', desc: 'AI 预问诊，医生接诊', action: 'navigateTo', url: '/pages/consult/entry/index' },
      { key: 'booking', icon: '⚑', label: '预约挂号', desc: '选科室、医生与时间', action: 'navigateTo', url: '/pages/booking/standard-departments/index' },
      { key: 'report', icon: '▦', label: '报告解读', desc: '上传报告，AI 解读', action: 'navigateTo', url: '/pages/report/index' },
    ],
  },
  {
    title: '健康管理',
    columns: 4,
    items: [
      { key: 'health', icon: '♡', label: '健康档案', action: 'navigateTo', url: '/pages/health/index' },
      { key: 'appointments', icon: '▤', label: '我的挂号', action: 'navigateTo', url: '/pages/appointments/index' },
      { key: 'prescriptions', icon: 'Rx', textIcon: true, label: '电子处方', action: 'navigateTo', url: '/pages/prescriptions/index' },
      { key: 'drugOrders', icon: '▣', label: '药品订单', action: 'navigateTo', url: '/pages/drug-orders/index' },
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

/** 待办横卡数据：即将就诊的挂号单（已约且日期不早于今天）+ 待支付药品订单。 */
function buildTodos(appointments, orders) {
  const today = todayString()
  const upcoming = (appointments || [])
    .filter((item) => item.status === '已约' && item.schedule_date >= today)
    .sort((a, b) => `${a.schedule_date} ${a.time_slot}`.localeCompare(`${b.schedule_date} ${b.time_slot}`))
    .map((item) => ({
      key: `appointment-${item.appointment_id}`,
      kind: 'appointment',
      badge: '即将就诊',
      title: `${item.department_name} · ${item.doctor_name}医生`,
      meta: `${item.schedule_date} ${item.time_slot} · 第 ${item.sequence_number} 号`,
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
  return [...upcoming, ...unpaid]
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
    // AI挂号助手主卡（票 49）：当前城市 + 最近 3 家医院 + 真实总数
    regCityName: '',
    regHospitals: [],
    regTotal: 0,
  },

  onShow() {
    this.load()
    this.loadRegistrationCard()
    this.promptLocationOnce()
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
      .then(({ cityName, hospitals, total }) =>
        this.setData({ regCityName: cityName, regHospitals: hospitals, regTotal: total })
      )
      .catch(() => {})
  },

  onDepartmentEntry() {
    my.navigateTo({ url: '/pages/booking/standard-departments/index' })
  },

  onGuideEntry() {
    my.switchTab({ url: '/pages/chat/index' })
  },

  onHospitalTap({ hospitalId, hospitalName }) {
    my.navigateTo({
      url: `/pages/booking/campuses/index?hospital_id=${hospitalId}&hospital_name=${encodeURIComponent(hospitalName)}`,
    })
  },

  onMoreHospitals() {
    my.navigateTo({ url: '/pages/booking/hospitals/index' })
  },

  onRelocate() {
    relocate().then((picked) => {
      if (picked) this.loadRegistrationCard()
    })
  },

  load() {
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
        })
      })
      .catch(() => my.showToast({ content: '加载失败，请稍后重试', type: 'fail' }))
  },

  onGridTap(e) {
    const { action, url } = e.currentTarget.dataset
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
