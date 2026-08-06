const { listCampuses } = require('../../../services/directory')
const { getCoords } = require('../../../utils/location')

/** 院区（CONTEXT.md 词条）：同一医院下具有独立地址与坐标的就诊地点，先选院区再选科室。 */
Page({
  data: {
    loading: true,
    hospitalId: 0,
    hospitalName: '',
    campuses: [],
  },

  onLoad(query) {
    const hospitalId = Number(query.hospital_id)
    const hospitalName = decodeURIComponent(query.hospital_name || '')
    this.setData({ hospitalId, hospitalName })
    // 导航栏标题直接用医院名，json 里的 defaultTitle 仅作加载前的兜底
    if (hospitalName) my.setNavigationBar({ title: hospitalName })
    this.loadCampuses()
  },

  loadCampuses() {
    this.setData({ loading: true })
    return listCampuses(this.data.hospitalId, getCoords())
      .then((campuses) => {
        campuses = (campuses || []).map((item) => ({
          ...item,
          distance_text: item.distance_km != null ? Number(item.distance_km).toFixed(1) : '',
        }))
        this.setData({ campuses })
      })
      .catch(() => my.showToast({ content: '院区列表加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },

  openDepartments(e) {
    const { id, name } = e.currentTarget.dataset
    my.navigateTo({
      url:
        `/pages/booking/departments/index?campus_id=${id}` +
        `&campus_name=${encodeURIComponent(name)}` +
        `&hospital_name=${encodeURIComponent(this.data.hospitalName)}`,
    })
  },
})
