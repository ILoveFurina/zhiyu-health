const { listCityHospitals } = require('../../../services/directory')
const { ensureCity, getCoords } = require('../../../utils/location')

/** 当前城市医院列表：城市是硬筛选边界（无本城市数据时空态，不跨城市推荐），按最近院区距离排序。 */
Page({
  data: {
    loading: true,
    cityName: '',
    hospitals: [],
    skelItems: [1, 2, 3],
  },

  onLoad() {
    ensureCity()
      .then((city) => this.loadHospitals(city))
      .catch(() => {
        this.setData({ loading: false })
        my.showToast({ content: '城市信息加载失败', type: 'fail' })
      })
  },

  loadHospitals(city) {
    if (!city) {
      this.setData({ loading: false, hospitals: [] })
      return Promise.resolve()
    }
    this.setData({ loading: true, cityName: city.city_name })
    return listCityHospitals(city.city_code, getCoords())
      .then((hospitals) => {
        // axml 无法调 toFixed，距离在 js 侧格式化为一位小数
        hospitals = (hospitals || []).map((item) => ({
          ...item,
          distance_text: item.distance_km != null ? Number(item.distance_km).toFixed(1) : '',
        }))
        this.setData({ hospitals })
      })
      .catch(() => my.showToast({ content: '医院列表加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },

  openCampuses(e) {
    const { id, name } = e.currentTarget.dataset
    my.navigateTo({
      url: `/pages/booking/campuses/index?hospital_id=${id}&hospital_name=${encodeURIComponent(name)}`,
    })
  },
})
