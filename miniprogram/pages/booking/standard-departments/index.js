const { listStandardDepartments } = require('../../../services/directory')
const { ensureCity } = require('../../../utils/location')

/**
 * 标准科室目录（CONTEXT.md 词条）：城市级「科类 → 标准科室」左右目录，
 * 搜索按科室名跨科类过滤（客户端过滤）；点标准科室进入科室号源卡。
 */
Page({
  data: {
    loading: true,
    cityName: '',
    categories: [], // [{ category, departments: [{ id, name }] }]
    activeIndex: 0,
    keyword: '',
    results: [], // 搜索态的跨科类扁平结果 [{ id, name, category }]
  },

  onLoad() {
    ensureCity()
      .then((city) => this.loadCategories(city))
      .catch(() => {
        this.setData({ loading: false })
        my.showToast({ content: '城市信息加载失败', type: 'fail' })
      })
  },

  loadCategories(city) {
    if (!city) {
      this.setData({ loading: false, categories: [] })
      return Promise.resolve()
    }
    this.setData({ loading: true, cityName: city.city_name })
    return listStandardDepartments(city.city_code)
      .then((categories) => this.setData({ categories: categories || [] }))
      .catch(() => my.showToast({ content: '科室目录加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },

  onSearchInput(e) {
    const keyword = e.detail.value.trim()
    const results = keyword
      ? this.data.categories.reduce(
          (acc, group) =>
            acc.concat(
              (group.departments || [])
                .filter((department) => department.name.includes(keyword))
                .map((department) => ({ ...department, category: group.category }))
            ),
          []
        )
      : []
    this.setData({ keyword, results })
  },

  onCategoryTap(e) {
    this.setData({ activeIndex: Number(e.currentTarget.dataset.index) })
  },

  openSlots(e) {
    const { id, name } = e.currentTarget.dataset
    my.navigateTo({
      url: `/pages/booking/department-slots/index?std_id=${id}&std_name=${encodeURIComponent(name)}`,
    })
  },
})
