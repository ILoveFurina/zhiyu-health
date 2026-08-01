const { createAppointment } = require('../../../services/directory')

Page({
  data: {
    scheduleId: 0,
    scheduleDate: '',
    timeSlot: '',
    doctorName: '',
    departmentName: '',
    hospitalName: '',
    fee: '',
    submitting: false,
  },

  onLoad(query) {
    this.setData({
      scheduleId: Number(query.scheduleId),
      scheduleDate: decodeURIComponent(query.scheduleDate || ''),
      timeSlot: decodeURIComponent(query.timeSlot || ''),
      doctorName: decodeURIComponent(query.doctorName || ''),
      departmentName: decodeURIComponent(query.departmentName || ''),
      hospitalName: decodeURIComponent(query.hospitalName || ''),
      fee: decodeURIComponent(query.fee || ''),
    })
  },

  confirmBooking() {
    // loading 态禁用按钮 + 前置判断双保险，防重复提交
    if (this.data.submitting) return
    this.setData({ submitting: true })
    createAppointment(this.data.scheduleId)
      .then(() => {
        my.showToast({ content: '挂号成功', type: 'success' })
        setTimeout(() => my.redirectTo({ url: '/pages/appointments/index' }), 800)
      })
      .catch((err) => {
        this.setData({ submitting: false })
        // request.js 已把 ApiException 错误体 detail 挂在 err.detail（如“号源已约满”“请勿重复挂号”）
        const detail = (err && err.detail) || '挂号失败，请稍后重试'
        my.showToast({ content: detail, type: 'fail' })
        // 号源耗尽：刷新上级排班页余量，用户返回时该时段已显示约满
        if (detail.includes('号源')) {
          const pages = getCurrentPages()
          const prev = pages[pages.length - 2]
          if (prev && prev.route === 'pages/booking/schedules/index' && prev.loadSchedules) {
            prev.loadSchedules()
          }
        }
      })
  },
})
