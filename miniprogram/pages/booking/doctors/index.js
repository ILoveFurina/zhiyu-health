const { listDoctors } = require('../../../services/directory')

Page({
  data: {
    loading: true,
    departmentId: 0,
    departmentName: '',
    hospitalName: '',
    doctors: [],
    // 头像加载失败降级文字圆（票 59）：key 为 doctor_id
    avatarFailed: {},
  },

  onLoad(query) {
    const departmentId = Number(query.departmentId)
    const departmentName = decodeURIComponent(query.departmentName || '')
    const hospitalName = decodeURIComponent(query.hospitalName || '')
    this.setData({ departmentId, departmentName, hospitalName })
    if (departmentName) my.setNavigationBar({ title: departmentName })
    this.loadDoctors()
  },

  loadDoctors() {
    this.setData({ loading: true })
    return listDoctors(this.data.departmentId)
      .then((doctors) => {
        // 无头像时降级为姓氏圆形占位
        doctors = doctors.map((item) => ({ ...item, initial: (item.name || '').slice(0, 1) }))
        this.setData({ doctors })
      })
      .catch(() => my.showToast({ content: '医生列表加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },

  onAvatarError(e) {
    const id = e.currentTarget.dataset.id
    if (id == null) return
    this.setData({ [`avatarFailed.${id}`]: true })
  },

  openSchedules(e) {
    const { id, name, fee } = e.currentTarget.dataset
    my.navigateTo({
      url:
        `/pages/booking/schedules/index?doctorId=${id}` +
        `&doctorName=${encodeURIComponent(name)}` +
        `&departmentName=${encodeURIComponent(this.data.departmentName)}` +
        `&hospitalName=${encodeURIComponent(this.data.hospitalName)}` +
        `&fee=${encodeURIComponent(fee)}`,
    })
  },
})
