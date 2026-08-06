const { listStandardDepartmentSlots } = require('../../../services/directory')
const { ensureCity, getCoords } = require('../../../utils/location')

/**
 * 科室号源卡页面：标准科室在当前城市的跨医院 14 天号源。
 * 点日期条带 date 重新拉取；预约跳转与排班页相同的确认页参数。
 */
Page({
  data: {
    loading: true,
    refreshing: false,
    stdId: 0,
    stdName: '',
    city: null,
    standardDepartment: null,
    days: [],
    selectedDate: '',
    doctors: [],
  },

  onLoad(query) {
    const stdId = Number(query.std_id)
    const stdName = decodeURIComponent(query.std_name || '')
    this.setData({ stdId, stdName })
    if (stdName) my.setNavigationBar({ title: stdName })
    ensureCity()
      .then((city) => {
        this.setData({ city })
        return this.load()
      })
      .catch(() => {
        this.setData({ loading: false })
        my.showToast({ content: '城市信息加载失败', type: 'fail' })
      })
  },

  load(date) {
    if (!this.data.city) {
      this.setData({ loading: false })
      return Promise.resolve()
    }
    const coords = getCoords()
    return listStandardDepartmentSlots(this.data.stdId, {
      cityCode: this.data.city.city_code,
      lat: coords.lat,
      lng: coords.lng,
      date,
    })
      .then((res) => {
        this.setData({
          standardDepartment: res.standard_department || null,
          days: res.days || [],
          selectedDate: date || (res.days && res.days[0]) || '',
          doctors: res.doctors || [],
        })
      })
      .catch(() => my.showToast({ content: '号源加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false, refreshing: false }))
  },

  onSelectDate(date) {
    if (!date || date === this.data.selectedDate) return
    // 已加载卡片保留展示，仅标记刷新中，避免日期切换时整卡闪空
    this.setData({ refreshing: true, selectedDate: date })
    this.load(date)
  },

  onBook({ doctor, slot }) {
    if (!slot || Number(slot.remaining_slots) <= 0) return
    const hospitalName = doctor.campus_name
      ? `${doctor.hospital_name} · ${doctor.campus_name}`
      : doctor.hospital_name
    my.navigateTo({
      url:
        `/pages/booking/confirm/index?scheduleId=${slot.schedule_id}` +
        `&scheduleDate=${encodeURIComponent(slot.schedule_date)}` +
        `&timeSlot=${encodeURIComponent(slot.time_slot)}` +
        `&doctorName=${encodeURIComponent(doctor.doctor_name)}` +
        `&departmentName=${encodeURIComponent(this.data.stdName)}` +
        `&hospitalName=${encodeURIComponent(hospitalName)}` +
        `&fee=${encodeURIComponent(doctor.registration_fee)}`,
    })
  },
})
