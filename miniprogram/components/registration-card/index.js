Component({
  props: {
    cityName: '',
    total: 0, // 当前城市平台医院真实总数
    onDepartmentEntry: () => {},
    onGuideEntry: () => {},
    onMoreHospitals: () => {},
  },

  methods: {
    tapDepartment() {
      this.props.onDepartmentEntry()
    },
    tapGuide() {
      this.props.onGuideEntry()
    },
    tapMore() {
      this.props.onMoreHospitals()
    },
  },
})
