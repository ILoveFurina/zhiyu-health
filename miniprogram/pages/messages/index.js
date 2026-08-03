const { ensureLogin } = require('../../utils/auth')
const { listMessages, listMedCheckins, checkMedCheckin } = require('../../services/patient-care')

Page({
  data: { loading: true, messages: [], reminders: [] },
  onShow() {
    ensureLogin()
      .then(() => Promise.all([listMessages(), listMedCheckins()]))
      .then(([messages, reminders]) => {
        this.setData({ messages, reminders })
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
