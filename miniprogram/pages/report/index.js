const { ensureLogin } = require('../../utils/auth')
const { chooseReport } = require('../../utils/report-picker')
const { stageReportFiles } = require('../../utils/report-upload')
const { currentProfile } = require('../../services/health-profiles')
const { list } = require('../../services/report-interpretations')

// 报告解读记录状态（server-java ReportInterpretation.status）→ 列表徽标
const STATUS_LABELS = {
  SUCCEEDED: '已完成',
  PROCESSING: '解读中',
  FAILED: '解读失败',
}

function decorate(item) {
  const result = item.result || {}
  return {
    ...item,
    statusLabel: STATUS_LABELS[item.status] || item.status,
    statusClass: (item.status || '').toLowerCase(),
    summary: result.summary || '',
    pageMeta: item.page_count ? `共 ${item.page_count} 页` : '',
  }
}

/**
 * 报告解读独立入口页（票 42 阶段三，对齐支付宝 AQ 模式：入口独立、过程在会话）。
 * 上传：复用 chat 同款链路——chooseReport 选图/同意说明，stageReportFiles 分段上传；
 * 随后经 globalData 把 request_id 交给 chat（switchTab 不能带参），由 chat 调 finalize
 * 并把解读过程渲染在会话流里（见 pages/chat/report-composer.js consumeReportEntry）。
 */
Page({
  data: {
    loading: true,
    records: [],
    hasProfile: null, // null=未加载完；false 时才拦截上传引导建档
    pending: null, // { items, isPdf }：待上传的报告文件
    uploading: false,
    uploadProgress: '',
  },

  onShow() {
    this.load()
  },

  load() {
    return ensureLogin()
      .then(() =>
        Promise.all([
          list(),
          // 档案只用于上传前引导，加载失败不阻塞记录列表
          currentProfile().catch(() => ({ profile: null })),
        ])
      )
      .then(([records, current]) => {
        this.setData({
          records: records.map(decorate),
          hasProfile: Boolean(current && current.profile),
          loading: false,
        })
      })
      .catch(() => {
        this.setData({ loading: false })
        my.showToast({ content: '加载失败，请稍后重试', type: 'fail' })
      })
  },

  pickReport(e) {
    if (this.data.uploading) return
    if (this.data.hasProfile === false) {
      my.showToast({ content: '请先创建健康档案', type: 'none' })
      my.navigateTo({ url: '/pages/health/index?create=1' })
      return
    }
    // index 与 chat 动作面板一致：0 拍摄 / 1 相册 / 2 PDF（chooseReport 内含同意说明）
    chooseReport(Number(e.currentTarget.dataset.index))
      .then((items) => this.setData({
        pending: { items, isPdf: items[0].kind === 'pdf' },
      }))
      .catch((error) => {
        if (error.message !== '已取消' && !error.message.startsWith('未选择')) {
          my.showToast({ content: error.message, type: 'fail' })
        }
      })
  },

  removePendingItem(e) {
    const items = this.data.pending.items.filter(
      (_, index) => index !== Number(e.currentTarget.dataset.index)
    )
    this.setData({ pending: items.length ? { ...this.data.pending, items } : null })
  },

  movePendingItem(e) {
    const from = Number(e.currentTarget.dataset.index)
    const to = from + Number(e.currentTarget.dataset.offset)
    const items = this.data.pending.items.slice()
    if (to < 0 || to >= items.length) return
    const swap = items[from]
    items[from] = items[to]
    items[to] = swap
    this.setData({ pending: { ...this.data.pending, items } })
  },

  cancelPending() {
    if (this.data.uploading) return
    this.setData({ pending: null })
  },

  confirmUpload() {
    const pending = this.data.pending
    if (!pending || this.data.uploading) return
    const items = pending.items
    const requestId = `report-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    this.setData({ uploading: true, uploadProgress: `正在上传 0/${items.length}` })
    ensureLogin()
      .then(() => stageReportFiles({
        requestId,
        items,
        onProgress: (done, total) => this.setData({ uploadProgress: `正在上传 ${done}/${total}` }),
      }))
      .then(() => {
        // 分段上传完成后交棒 chat：chat onShow 消费 pendingReportFinalize 调 finalize 并渲染解读
        getApp().globalData.pendingReportFinalize = { requestId, items }
        this.setData({ pending: null, uploading: false, uploadProgress: '' })
        my.switchTab({ url: '/pages/chat/index' })
      })
      .catch((error) => {
        this.setData({ uploading: false, uploadProgress: '' })
        my.showToast({ content: error.message || '无法上传报告，请检查网络', type: 'fail' })
      })
  },

  openRecord(e) {
    // 统一先进详情页（票 61）；详情页内再提供「查看原会话」入口
    my.navigateTo({ url: `/pages/report/detail/index?id=${e.currentTarget.dataset.id}` })
  },
})
