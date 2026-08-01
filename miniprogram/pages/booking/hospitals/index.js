const { ensureLogin } = require('../../../utils/auth')
const { listHospitals } = require('../../../services/directory')

Page({
  data: {
    loading: true,
    located: false,
    hospitals: [],
  },

  onLoad() {
    ensureLogin()
      .then(() => this.loadWithLocation())
      .catch(() => {
        this.setData({ loading: false })
        my.showToast({ content: '登录失败，请稍后重试', type: 'fail' })
      })
  },

  /** 用户拒绝定位或定位失败时静默降级：不带坐标调目录接口，由后端给默认排序。 */
  loadWithLocation() {
    my.getLocation({
      type: 1,
      success: (res) => this.loadHospitals({ lat: res.latitude, lng: res.longitude }, true),
      fail: () => this.loadHospitals({}, false),
    })
  },

  loadHospitals(coords, located) {
    this.setData({ loading: true })
    return listHospitals(coords)
      .then((hospitals) => {
        // axml 无法调 toFixed，距离在 js 侧格式化为一位小数
        hospitals = hospitals.map((item) => ({
          ...item,
          distance_text: item.distance_km != null ? Number(item.distance_km).toFixed(1) : '',
        }))
        this.setData({ hospitals, located })
      })
      .catch(() => my.showToast({ content: '医院列表加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },

  openDepartments(e) {
    const { id, name } = e.currentTarget.dataset
    my.navigateTo({
      url: `/pages/booking/departments/index?hospitalId=${id}&hospitalName=${encodeURIComponent(name)}`,
    })
  },
})
