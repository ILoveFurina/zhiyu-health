const { ensureLogin } = require('../../utils/auth')
const { chooseReport } = require('../../utils/report-picker')
const { uploadReport } = require('../../utils/report-upload')

module.exports = {
  openReportPicker() {
    if (this.data.sending) return
    my.showActionSheet({
      items: ['拍摄报告', '从相册选择', '上传 PDF'],
      success: (result) => {
        chooseReport(result.index)
          .then((items) => this.setData({
            pendingReport: { items, isPdf: items[0].kind === 'pdf' },
          }))
          .catch((error) => {
            if (error.message !== '已取消' && !error.message.startsWith('未选择')) {
              my.showToast({ content: error.message, type: 'fail' })
            }
          })
      },
    })
  },

  removeReportItem(e) {
    const items = this.data.pendingReport.items.filter(
      (_, index) => index !== Number(e.currentTarget.dataset.index)
    )
    this.setData({ pendingReport: items.length ? { ...this.data.pendingReport, items } : null })
  },

  moveReportItem(e) {
    const from = Number(e.currentTarget.dataset.index)
    const to = from + Number(e.currentTarget.dataset.offset)
    const items = this.data.pendingReport.items.slice()
    if (to < 0 || to >= items.length) return
    const swap = items[from]
    items[from] = items[to]
    items[to] = swap
    this.setData({ pendingReport: { ...this.data.pendingReport, items } })
  },

  sendPendingReport() {
    if (!this.data.pendingReport || this.data.sending) return
    const items = this.data.pendingReport.items
    const requestId = `report-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    this.setData({ sending: true, reportProgress: `正在上传 0/${items.length}` })
    ensureLogin()
      .then(() => uploadReport({
        requestId,
        conversationId: this.data.conversationId,
        items,
        onProgress: (done, total) => this.setData({ reportProgress: `正在上传 ${done}/${total}` }),
      }))
      .then((data) => this.finishReport(items, data))
      .catch((error) => {
        this.setData({ sending: false, reportProgress: '' })
        my.showToast({ content: error.message || '报告解读失败', type: 'fail' })
      })
  },

  finishReport(items, data) {
    const uploadMessage = {
      id: ++this._msgSeq, role: 'user', kind: 'report_upload',
      content: items[0].kind === 'pdf' ? items[0].name : `报告图片（${items.length}张）`,
    }
    const resultMessage = {
      id: ++this._msgSeq, role: 'assistant', kind: 'report_interpretation',
      card: data.result, disclaimer: data.disclaimer,
    }
    this.setData({
      messages: [...this.data.messages, uploadMessage, resultMessage],
      conversationId: data.conversation_id,
      pendingReport: null,
      reportProgress: '',
      sending: false,
      anchorId: 'thread-bottom',
    })
  },
}
