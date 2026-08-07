// 票 54：在线问诊五步进度条（契约 progress_steps 的端侧呈现，不依赖 antd-mini）。
const { PROGRESS_STEPS } = require('../../utils/consultation')

Component({
  props: {
    // 当前进度 key（PRECONSULTATION/SUMMARY_CONFIRMED/WAITING_DOCTOR/IN_PROGRESS/COMPLETED）；
    // null 表示终态分支（CANCELLED/EXPIRED），五步全部中性展示，终态提示由页面另行渲染
    current: null,
  },

  data: {
    steps: PROGRESS_STEPS,
    currentIndex: -1,
  },

  didMount() {
    this.syncIndex(this.props.current)
  },

  deriveDataFromProps(nextProps) {
    this.syncIndex(nextProps.current)
  },

  methods: {
    syncIndex(current) {
      const currentIndex = PROGRESS_STEPS.findIndex((step) => step.key === current)
      if (currentIndex !== this.data.currentIndex) {
        this.setData({ currentIndex })
      }
    },
  },
})
