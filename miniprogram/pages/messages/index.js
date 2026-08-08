const { ensureLogin } = require('../../utils/auth')
const { listMessages, listMedCheckins, checkMedCheckin } = require('../../services/patient-care')

Page({
  data: { loading: true, messages: [], reminders: [], skelItems: [1, 2, 3, 4] },
  onShow() {
    ensureLogin()
      .then(() => Promise.all([listMessages(), listMedCheckins()]))
      .then(([messages, reminders]) => {
        // 就诊指引卡（票 43）：appointment_care 的 content 是结构化 JSON，解析后挂到 item.care 供卡片渲染。
        const decorated = messages.map((item) => {
          if (item.type !== 'appointment_care') return item
          try {
            return { ...item, care: JSON.parse(item.content) }
          } catch (e) {
            return item
          }
        })
        this.setData({ messages: decorated, reminders })
      })
      .catch(() => my.showToast({ content: '消息加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },
  // 服药打卡：点击"已服用"推进 PENDING->CHECKED，幂等不报错，成功后从提醒列表移除（ADR-0017）。
  onCheckIn(e) {
    const id = e.currentTarget.dataset.id
    checkMedCheckin(id)
      .then((view) => {
        my.showToast({ content: `已打卡，连续 ${view.streak} 天`, type: 'success' })
        this.setData({ reminders: this.data.reminders.filter((r) => r.id !== id) })
      })
      .catch(() => my.showToast({ content: '打卡失败，请重试', type: 'fail' }))
  },
})
