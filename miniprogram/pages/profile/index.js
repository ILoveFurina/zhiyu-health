const { ensureLogin } = require('../../utils/auth')

// 「我的」入口清单：报告解读记录页属票 42 阶段二，本阶段先落入口
const SECTIONS = [
  {
    title: '健康服务',
    items: [{ key: 'health', icon: 'heart', label: '健康档案管理', desc: '基础信息、过敏史与健康指标', url: '/pages/health/index' }],
  },
  {
    title: '我的记录',
    items: [
      { key: 'appointments', icon: 'ticket', label: '我的挂号', desc: '查看就诊时间与挂号凭证', url: '/pages/appointments/index' },
      { key: 'prescriptions', icon: 'file', label: '电子处方', desc: '查看医生处方与审核结果', url: '/pages/prescriptions/index' },
      { key: 'drugOrders', icon: 'capsule', label: '药品订单', desc: '订单状态与药品明细', url: '/pages/drug-orders/index' },
      { key: 'reports', icon: 'report', label: '报告解读记录', desc: '回看上传报告与 AI 解读', url: '/pages/report/index' },
    ],
  },
  {
    title: '其他',
    items: [{ key: 'messages', icon: 'mail', label: '消息中心', desc: '就诊提醒、服药打卡与通知', url: '/pages/messages/index' }],
  },
]

Page({
  data: {
    sections: SECTIONS,
    nickname: '',
    avatarChar: '',
  },

  onShow() {
    // mock 登录态下账号信息来自本地缓存（PatientInfo 仅 id + nickname，无头像字段）
    ensureLogin()
      .then(() => {
        const stored = my.getStorageSync({ key: 'patient' })
        const patient = stored.data || null
        const nickname = (patient && patient.nickname) || '智愈用户'
        this.setData({ nickname, avatarChar: nickname.slice(0, 1) })
      })
      .catch(() => this.setData({ nickname: '智愈用户', avatarChar: '智' }))
  },

  open(e) {
    my.navigateTo({ url: e.currentTarget.dataset.url })
  },
})
