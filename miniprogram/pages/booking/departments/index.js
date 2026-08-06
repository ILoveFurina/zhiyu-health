const { listCampusDepartments } = require('../../../services/directory')

/** 院区实际科室：归属具体院区，并按医院科室分类（category_name）展示。 */
Page({
  data: {
    loading: true,
    campusId: 0,
    campusName: '',
    hospitalName: '',
    departments: [],
  },

  onLoad(query) {
    const campusId = Number(query.campus_id)
    const campusName = decodeURIComponent(query.campus_name || '')
    const hospitalName = decodeURIComponent(query.hospital_name || '')
    this.setData({ campusId, campusName, hospitalName })
    if (campusName) my.setNavigationBar({ title: campusName })
    this.loadDepartments()
  },

  loadDepartments() {
    this.setData({ loading: true })
    return listCampusDepartments(this.data.campusId)
      .then((departments) => this.setData({ departments: departments || [] }))
      .catch(() => my.showToast({ content: '科室列表加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },

  openDoctors(e) {
    const { id, name } = e.currentTarget.dataset
    // 确认页展示「就诊医院」为医院 + 院区，沿 doctors → schedules → confirm 透传
    const displayHospital = this.data.campusName
      ? `${this.data.hospitalName} · ${this.data.campusName}`
      : this.data.hospitalName
    my.navigateTo({
      url:
        `/pages/booking/doctors/index?departmentId=${id}` +
        `&departmentName=${encodeURIComponent(name)}` +
        `&hospitalName=${encodeURIComponent(displayHospital)}`,
    })
  },
})
