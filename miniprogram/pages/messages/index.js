const { ensureLogin } = require('../../utils/auth')
const { listMessages, listMedCheckins, checkMedCheckin } = require('../../services/patient-care')
const { MESSAGE_TYPES } = require('../../utils/appointment')

const READ_MESSAGE_IDS_KEY = 'readInAppMessageIds'

function readMessageIds() {
  const stored = my.getStorageSync({ key: READ_MESSAGE_IDS_KEY }).data
  return Array.isArray(stored) ? stored.map(String) : []
}

Page({
  data: { loading: true, messages: [], reminders: [], skelItems: [1, 2, 3, 4] },
  onShow() {
    ensureLogin()
      .then(() => Promise.all([listMessages(), listMedCheckins()]))
      .then(([messages, reminders]) => {
        const readIds = readMessageIds()
        // 就诊指引卡（票 43）：appointment_care 的 content 是结构化 JSON，解析后挂到 item.care 供卡片渲染。
        const decorated = messages.map((item) => {
          const withReadState = { ...item, isUnread: !readIds.includes(String(item.id)) }
          if (item.type !== 'appointment_care' && item.type !== MESSAGE_TYPES.called) return withReadState
          try {
            const content = JSON.parse(item.content)
            return item.type === MESSAGE_TYPES.called
              ? { ...withReadState, call: content, isCallNotice: true }
              : { ...withReadState, care: content }
          } catch (e) {
            return withReadState
          }
        })
        this.setData({ messages: decorated, reminders })
      })
      .catch(() => my.showToast({ content: '消息加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },
  // 站内消息暂无服务端已读字段，视觉已读态仅保存在本机，不改变消息业务数据。
  onMessageTap(e) {
    const id = String(e.currentTarget.dataset.id)
    const readIds = readMessageIds()
    if (!readIds.includes(id)) {
      my.setStorageSync({ key: READ_MESSAGE_IDS_KEY, data: [...readIds, id] })
    }
    this.setData({
      messages: this.data.messages.map((message) =>
        String(message.id) === id ? { ...message, isUnread: false } : message
      ),
    })
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
