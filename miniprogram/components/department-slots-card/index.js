const WEEK_LABELS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** 本地当天 YYYY-MM-DD，用于标记日期条上的「今天」。 */
function todayString() {
  const now = new Date()
  const month = `${now.getMonth() + 1}`.padStart(2, '0')
  const day = `${now.getDate()}`.padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

/**
 * 科室号源卡（CONTEXT.md 词条，票 49；票 50 复用为 Agent 卡片出口）：
 * 当前城市标准科室未来 14 天的跨医院号源。有号医生优先（server-java 已排序），
 * 无号医生保留展示并禁用预约；医院、院区、距离、余号全部来自 server-java。
 */
Component({
  props: {
    standardDepartment: {}, // { id, name, category }
    days: [], // 今天起连续 14 天 yyyy-MM-dd
    selectedDate: '',
    doctors: [],
    onSelectDate: () => {},
    onBook: () => {},
  },

  data: {
    daysView: [],
    doctorsView: [],
  },

  // axml 无法调 toFixed/new Date，日期条元信息与距离在 js 侧派生
  deriveDataFromProps(nextProps) {
    const slotDates = new Set()
    ;(nextProps.doctors || []).forEach((doctor) =>
      (doctor.slots || []).forEach((slot) => {
        if (slot.remaining_slots > 0) slotDates.add(slot.schedule_date)
      })
    )
    const today = todayString()
    const daysView = (nextProps.days || []).map((date) => {
      const day = new Date(`${date}T00:00:00`)
      return {
        date,
        day_label: `${day.getMonth() + 1}/${day.getDate()}`,
        week_label: date === today ? '今天' : WEEK_LABELS[day.getDay()],
        has_slot: slotDates.has(date),
        active: date === nextProps.selectedDate,
      }
    })
    // 首屏不带 date 时 server-java 返回窗口内 14 天全部排班（供上方日期条圆点判定），
    // 卡片时段必须按选中日期过滤，否则单个医生的上午/下午会堆叠几十条
    const selectedDate = nextProps.selectedDate
    const doctorsView = (nextProps.doctors || []).map((doctor) => ({
      ...doctor,
      slots: selectedDate
        ? (doctor.slots || []).filter((slot) => slot.schedule_date === selectedDate)
        : doctor.slots || [],
      distance_text: doctor.distance_km != null ? Number(doctor.distance_km).toFixed(1) : '',
    }))
    this.setData({ daysView, doctorsView })
  },

  methods: {
    selectDate(e) {
      this.props.onSelectDate(e.currentTarget.dataset.date)
    },

    book(e) {
      const { doctorIndex, slotIndex } = e.currentTarget.dataset
      const doctor = this.data.doctorsView[doctorIndex]
      const slot = doctor && doctor.slots[slotIndex]
      // 已约满仅置灰展示，仍在 js 侧按剩余号源挡一次，防御点按时数据已滞后
      if (!slot || Number(slot.remaining_slots) <= 0) return
      this.props.onBook({ scheduleId: slot.schedule_id, doctor, slot })
    },
  },
})
