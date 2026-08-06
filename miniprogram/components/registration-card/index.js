Component({
  props: {
    cityName: '',
    hospitals: [], // 最近 ≤3 家，距离已在页面侧格式化为 distance_text
    total: 0, // 当前城市平台医院真实总数
    onDepartmentEntry: () => {},
    onGuideEntry: () => {},
    onHospitalTap: () => {},
    onMoreHospitals: () => {},
    onRelocate: () => {},
  },

  methods: {
    tapDepartment() {
      this.props.onDepartmentEntry()
    },
    tapGuide() {
      this.props.onGuideEntry()
    },
    tapHospital(e) {
      this.props.onHospitalTap({
        hospitalId: e.currentTarget.dataset.id,
        hospitalName: e.currentTarget.dataset.name,
      })
    },
    tapMore() {
      this.props.onMoreHospitals()
    },
    tapRelocate() {
      this.props.onRelocate()
    },
  },
})
