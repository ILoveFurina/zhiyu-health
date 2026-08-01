const { listSchedules } = require('../../../services/directory')

/** 按出诊日期分组，保持接口返回顺序；确认页号源耗尽时会回调本页 loadSchedules 刷新余量。 */
function groupByDate(schedules) {
  const groups = []
  schedules.forEach((item) => {
    let group = groups.find((entry) => entry.date === item.schedule_date)
    if (!group) {
      group = { date: item.schedule_date, items: [] }
      groups.push(group)
    }
    group.items.push(item)
  })
  return groups
}

Page({
  data: {
    loading: true,
    doctorId: 0,
    doctorName: '',
    departmentName: '',
    hospitalName: '',
    fee: '',
    groups: [],
  },

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

  loadSchedules() {
    this.setData({ loading: true })
    return listSchedules(this.data.doctorId)
      .then((schedules) => this.setData({ groups: groupByDate(schedules) }))
      .catch(() => my.showToast({ content: '排班加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
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
