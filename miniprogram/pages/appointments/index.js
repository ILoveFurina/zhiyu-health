const { ensureLogin } = require('../../utils/auth')
const { listAppointments, cancelAppointment } = require('../../services/appointments')
const { currentProfile } = require('../../services/health-profiles')

Page({
  data: {
    loading: true,
    appointments: [],
    currentProfile: null,
  },

  onShow() {
    ensureLogin().then(() => this.loadAppointments())
  },

  loadAppointments() {
    this.setData({ loading: true })
    Promise.all([listAppointments(), currentProfile()])
      .then(([appointments, profileResult]) =>
        this.setData({ appointments, currentProfile: profileResult.profile })
      )
      .catch(() => my.showToast({ content: '挂号记录加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
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
  openPrescriptions() { my.navigateTo({ url: '/pages/prescriptions/index' }) },
  openMessages() { my.navigateTo({ url: '/pages/messages/index' }) },
  openHealthProfiles() { my.navigateTo({ url: '/pages/health/index' }) },
})
