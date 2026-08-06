const { ensureLogin } = require('../../utils/auth')
const { request } = require('../../utils/request')

/**
 * 查药品（文字版）composer（票 14，ADR-0025 差异化点 4）：
 * 文字输入药名 -> server-java MedicationLookupService 直查 -> 双出口卡片回落。
 * 与拍照版共用同一查询与规则出口，只是输入来源不同。
 */
module.exports = {
  openMedicationLookup() {
    if (this.data.sending) return
    this.setData({ pendingMedLookup: { name: '' } })
  },

  onMedLookupInput(e) {
    this.setData({ pendingMedLookup: { name: e.detail.value || '' } })
  },

  cancelMedLookup() {
    this.setData({ pendingMedLookup: null })
  },

  sendPendingMedLookup() {
    const name = (this.data.pendingMedLookup && this.data.pendingMedLookup.name || '').trim()
    if (!name || this.data.sending) return
    const requestId = `medlookup-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    this.setData({ sending: true, medLookupProgress: '正在查询药品…' })
    ensureLogin()
      .then(() => request({
        url: '/c/medication-lookups',
        method: 'POST',
        data: {
          request_id: requestId,
          medication_name: name,
          conversation_id: this.data.conversationId || undefined,
        },
        timeout: 60000,
      }))
      .then((data) => this.finishMedLookup(data))
      .catch((error) => {
        this.setData({ sending: false, medLookupProgress: '' })
        my.showToast({ content: error.message || '药品查询失败', type: 'fail' })
      })
  },

  finishMedLookup(data) {
    const newMessages = []
    if (data.not_found) {
      // not_found：响应携带 hint，前端追加文本气泡展示后端已落库的引导文案（票 46 延伸修复）。
      if (data.hint) {
        newMessages.push({
          id: ++this._msgSeq, role: 'assistant', kind: 'text',
          content: data.hint,
        })
      }
    } else {
      // 双出口：medication_info + medication_safety 两条独立 AI 消息
      newMessages.push({
        id: ++this._msgSeq, role: 'assistant', kind: 'medication_info',
        card: data.medication_info,
        disclaimer: data.disclaimer || '仅供参考，不替代医生诊断',
      })
      newMessages.push({
        id: ++this._msgSeq, role: 'assistant', kind: 'medication_safety',
        card: data.medication_safety,
        disclaimer: data.disclaimer || '仅供参考，不替代医生诊断',
      })
      // 未填过敏史时后端追加的 agent 提醒（响应携带 reminder），不阻断查药。
      if (data.reminder) {
        newMessages.push({
          id: ++this._msgSeq, role: 'assistant', kind: 'text',
          content: data.reminder,
        })
      }
    }
    this.setData({
      messages: [...this.data.messages, ...newMessages],
      conversationId: data.conversation_id,
      pendingMedLookup: null,
      medLookupProgress: '',
      sending: false,
      anchorId: 'thread-bottom',
    })
  },
}
