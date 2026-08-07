const {
  getConsultation,
  cancelConsultation,
  resubmitConsultation,
} = require('../../../services/consultation')
const { STATUSES, TEXTS, waitingMatchingText, terminalHintFor } = require('../../../utils/consultation')

const POLL_INTERVAL = 3000

/** 剩余接诊时限 mm:ss；expires_at 缺失或非法时返回空串不展示。 */
function countdownText(expiresAt) {
  if (!expiresAt) return ''
  const remainMs = new Date(expiresAt).getTime() - Date.now()
  if (Number.isNaN(remainMs)) return ''
  const total = Math.max(0, Math.floor(remainMs / 1000))
  const minutes = `${Math.floor(total / 60)}`.padStart(2, '0')
  const seconds = `${total % 60}`.padStart(2, '0')
  return `${minutes}:${seconds}`
}

/**
 * 等待医生接诊页（票 54）：每 3s 轮询问诊单详情。
 * IN_PROGRESS/COMPLETED -> 转医生问诊页；CANCELLED/EXPIRED -> 终态视图（可复用摘要重新提交）。
 * 轮询为页面级 setInterval，onHide/onUnload 必清理。
 */
Page({
  data: {
    id: null,
    loading: true,
    consultation: null,
    progressStep: null,
    waitingText: '',
    countdown: '',
    terminal: false,
    terminalHint: '',
    resubmitHint: '',
    cancelling: false,
    resubmitting: false,
  },

  _timer: null,
  _polling: false,
  _errorToasted: false,

  onLoad(query) {
    this.setData({ id: query && query.id })
  },

  onShow() {
    this.startPolling()
  },

  onHide() {
    this.stopPolling()
  },

  onUnload() {
    this.stopPolling()
  },

  startPolling() {
    this.stopPolling()
    this.poll()
    this._timer = setInterval(() => this.poll(), POLL_INTERVAL)
  },

  stopPolling() {
    if (this._timer) {
      clearInterval(this._timer)
      this._timer = null
    }
  },

  poll() {
    // 上一次轮询未返回时跳过，避免慢网下请求叠加
    if (this._polling || !this.data.id) return
    this._polling = true
    getConsultation(this.data.id)
      .then((res) => this.applyConsultation(res && res.consultation))
      .catch((err) => {
        // 首屏加载失败提示一次；轮询中的瞬时失败静默，下轮自愈
        if (this.data.loading && !this._errorToasted) {
          this._errorToasted = true
          my.showToast({ content: (err && err.detail) || '问诊单加载失败', type: 'fail' })
        }
      })
      .then(() => {
        this._polling = false
      })
  },

  applyConsultation(consultation) {
    if (!consultation) return
    this._errorToasted = false
    const status = consultation.status
    if (status === STATUSES.in_progress || status === STATUSES.completed) {
      // 已接诊/已完成都转医生问诊页（完成为只读视图）
      this.stopPolling()
      my.redirectTo({ url: `/pages/consult/doctor/index?id=${consultation.id}` })
      return
    }
    const terminal = status === STATUSES.cancelled || status === STATUSES.expired
    this.setData({
      loading: false,
      consultation,
      terminal,
      // 终态分支不占进度步：五步全部中性展示，提示文案单独呈现
      progressStep: terminal ? null : 'WAITING_DOCTOR',
      waitingText: terminal ? '' : waitingMatchingText(consultation.standard_department_name),
      countdown: terminal ? '' : countdownText(consultation.expires_at),
      terminalHint: terminal ? terminalHintFor(consultation) : '',
      resubmitHint: terminal ? TEXTS.resubmit_hint : '',
    })
    if (terminal) this.stopPolling()
  },

  onCancel() {
    if (this.data.cancelling || this.data.terminal) return
    my.confirm({
      title: '取消问诊',
      content: '确定取消本次在线问诊吗？',
      confirmButtonText: '确定取消',
      cancelButtonText: '再想想',
      success: (res) => {
        if (!res.confirm) return
        this.setData({ cancelling: true })
        cancelConsultation(this.data.id)
          .then((result) => this.applyConsultation(result && result.consultation))
          .catch((err) =>
            my.showToast({ content: (err && err.detail) || '取消失败，请稍后重试', type: 'fail' })
          )
          .then(() => this.setData({ cancelling: false }))
      },
    })
  },

  /** 重新提交：复用原摘要生成新问诊单，此后一律使用新 id。 */
  onResubmit() {
    if (this.data.resubmitting) return
    this.setData({ resubmitting: true })
    resubmitConsultation(this.data.id)
      .then((res) => {
        const next = res && res.consultation
        if (!next) throw new Error('重新提交失败')
        my.redirectTo({ url: `/pages/consult/waiting/index?id=${next.id}` })
      })
      .catch((err) => {
        my.showToast({ content: (err && err.detail) || '重新提交失败，请稍后重试', type: 'fail' })
        this.setData({ resubmitting: false })
      })
  },

  backHome() {
    my.switchTab({ url: '/pages/home/index' })
  },
})
