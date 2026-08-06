const { listSchedules } = require('../../../services/directory')

const WEEK_LABELS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** 本地当天起连续 14 个自然日；has_schedule 由排班接口结果回填，首日为「今天」。 */
function buildDays() {
  const days = []
  const now = new Date()
  for (let offset = 0; offset < 14; offset++) {
    const day = new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset)
    const month = `${day.getMonth() + 1}`.padStart(2, '0')
    const date = `${day.getDate()}`.padStart(2, '0')
    days.push({
      date: `${day.getFullYear()}-${month}-${date}`,
      day_label: `${day.getMonth() + 1}/${day.getDate()}`,
      week_label: offset === 0 ? '今天' : WEEK_LABELS[day.getDay()],
      has_schedule: false,
    })
  }
  return days
}

Page({
  data: {
    loading: true,
    doctorId: 0,
    doctorName: '',
    departmentName: '',
    hospitalName: '',
    fee: '',
    days: buildDays(),
    selectedDate: buildDays()[0].date,
    slots: [],
  },

  _schedules: [],

  onLoad(query) {
    this.setData({
      doctorId: Number(query.doctorId),
      doctorName: decodeURIComponent(query.doctorName || ''),
      departmentName: decodeURIComponent(query.departmentName || ''),
      hospitalName: decodeURIComponent(query.hospitalName || ''),
      fee: decodeURIComponent(query.fee || ''),
    })
    if (this.data.doctorName) my.setNavigationBar({ title: `${this.data.doctorName} 医生排班` })
    this.loadSchedules()
  },

  /** 确认页号源耗尽时会回调本页 loadSchedules 刷新余量。 */
  loadSchedules() {
    this.setData({ loading: true })
    return listSchedules(this.data.doctorId)
      .then((schedules) => {
        this._schedules = schedules || []
        const scheduledDates = new Set(this._schedules.map((item) => item.schedule_date))
        const days = buildDays().map((day) => ({ ...day, has_schedule: scheduledDates.has(day.date) }))
        this.setData({ days, slots: this.filterSlots(this.data.selectedDate) })
      })
      .catch(() => my.showToast({ content: '排班加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },

  /** 选中日的上午/下午时段，保持接口返回顺序。 */
  filterSlots(date) {
    return this._schedules.filter((item) => item.schedule_date === date)
  },

  onSelectDay(e) {
    const date = e.currentTarget.dataset.date
    if (date === this.data.selectedDate) return
    this.setData({ selectedDate: date, slots: this.filterSlots(date) })
  },

  openConfirm(e) {
    const { id, date, slot, remaining } = e.currentTarget.dataset
    // 约满时段仅置灰展示，仍在此按剩余号源挡一次，防御点按时数据已滞后
    if (Number(remaining) <= 0) return
    my.navigateTo({
      url:
        `/pages/booking/confirm/index?scheduleId=${id}` +
        `&scheduleDate=${encodeURIComponent(date)}` +
        `&timeSlot=${encodeURIComponent(slot)}` +
        `&doctorName=${encodeURIComponent(this.data.doctorName)}` +
        `&departmentName=${encodeURIComponent(this.data.departmentName)}` +
        `&hospitalName=${encodeURIComponent(this.data.hospitalName)}` +
        `&fee=${encodeURIComponent(this.data.fee)}`,
    })
  },
})
