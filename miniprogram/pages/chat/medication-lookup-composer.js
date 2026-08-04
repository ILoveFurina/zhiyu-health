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
      // 未匹配：后端已落 text 消息，前端补一条提示
      newMessages.push({
        id: ++this._msgSeq, role: 'assistant', kind: 'text',
        content: '未找到匹配的药品，请核对药名或咨询医生/药师。',
        disclaimer: '仅供参考，不替代医生诊断',
      })
    } else {
      // 双出口：medication_info + medication_safety 两条独立 AI 消息
      newMessages.push({
        id: ++this._msgSeq, role: 'assistant', kind: 'medication_info',
        card: data.medication_info,
        disclaimer: '仅供参考，不替代医生诊断',
      })
      newMessages.push({
        id: ++this._msgSeq, role: 'assistant', kind: 'medication_safety',
        card: data.medication_safety,
        disclaimer: '仅供参考，不替代医生诊断',
      })
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
