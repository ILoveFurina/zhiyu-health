const { ensureLogin } = require('../../utils/auth')
const { chooseReport } = require('../../utils/report-picker')
const { uploadReport, finalizeReport } = require('../../utils/report-upload')

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

  /**
   * 消费报告解读入口页（pages/report）传来的待解读请求（票 42 阶段三）：
   * 分段上传已在入口页完成，这里只调 finalize，解读过程复用会话流渲染。
   * switchTab 不能带参，经 globalData 传递；sending 时不消费、保留到下次 onShow，
   * 避免打断进行中的对话轮次；request_id 幂等，重复 finalize 返回既有记录。
   */
  consumeReportEntry() {
    const app = getApp()
    const entry = app.globalData.pendingReportFinalize
    if (!entry || this.data.sending) return
    app.globalData.pendingReportFinalize = null
    // 独立入口发起的解读归入全新会话：resetChatState 只清前端态，finalize 传空 conversation_id
    this.resetChatState()
    const uploadMessage = {
      id: ++this._msgSeq, role: 'user', kind: 'report_upload',
      content: entry.items[0].kind === 'pdf'
        ? entry.items[0].name
        : `报告图片（${entry.items.length}张）`,
    }
    const waiting = {
      id: ++this._msgSeq, role: 'assistant', kind: 'text',
      content: '报告解读中，请稍候…', disclaimer: '', streaming: true,
    }
    this.setData({ messages: [uploadMessage, waiting], sending: true, anchorId: 'thread-bottom' })
    ensureLogin()
      .then(() => finalizeReport({ requestId: entry.requestId, conversationId: null }))
      .then((data) => {
        this.patchMessage(waiting.id, () => ({
          id: waiting.id,
          role: 'assistant',
          kind: 'report_interpretation',
          card: data.result,
          disclaimer: data.disclaimer,
        }))
        this.setData({ conversationId: data.conversation_id, sending: false })
      })
      .catch((error) => {
        this.patchMessage(waiting.id, (msg) => ({
          ...msg,
          content: `抱歉，${error.detail || error.message || '报告解读失败'}，请稍后重试`,
          streaming: false,
        }))
        this.setData({ sending: false })
      })
  },
}
