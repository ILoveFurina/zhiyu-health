const { listDepartments } = require('../../../services/directory')

Page({
  data: {
    loading: true,
    hospitalId: 0,
    hospitalName: '',
    departments: [],
  },

  onLoad(query) {
    const hospitalId = Number(query.hospitalId)
    const hospitalName = decodeURIComponent(query.hospitalName || '')
    this.setData({ hospitalId, hospitalName })
    // 导航栏标题直接用医院名，json 里的 defaultTitle 仅作加载前的兜底
    if (hospitalName) my.setNavigationBar({ title: hospitalName })
    this.loadDepartments()
  },

  loadDepartments() {
    this.setData({ loading: true })
    return listDepartments(this.data.hospitalId)
      .then((departments) => this.setData({ departments }))
      .catch(() => my.showToast({ content: '科室列表加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },

  openDoctors(e) {
    const { id, name } = e.currentTarget.dataset
    my.navigateTo({
      url:
        `/pages/booking/doctors/index?departmentId=${id}` +
        `&departmentName=${encodeURIComponent(name)}` +
        `&hospitalName=${encodeURIComponent(this.data.hospitalName)}`,
    })
  },
})
