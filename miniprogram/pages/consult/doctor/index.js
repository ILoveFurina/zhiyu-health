const {
  getConsultation,
  listMessages,
  sendMessage,
} = require('../../../services/consultation')
const {
  STATUSES,
  CONSULT_METHODS,
  CONSULT_METHOD_LABELS,
  SENDER_TYPES,
  TEXTS,
  terminalHintFor,
} = require('../../../utils/consultation')

const POLL_INTERVAL = 3000

/** 模拟视频已进行时长 mm:ss；method_started_at 缺失或非法时返回空串。 */
function elapsedText(startedAt) {
  if (!startedAt) return ''
  const elapsedMs = Date.now() - new Date(startedAt).getTime()
  if (Number.isNaN(elapsedMs)) return ''
  const total = Math.max(0, Math.floor(elapsedMs / 1000))
  const minutes = `${Math.floor(total / 60)}`.padStart(2, '0')
  const seconds = `${total % 60}`.padStart(2, '0')
  return `${minutes}:${seconds}`
}

/**
 * 医生问诊页（票 55）：每 3s 轮询问诊单详情 + 增量拉取医患消息（after_id）。
 * VIDEO 接诊方式展示模拟视频面板（纯 UI，不接真实音视频）；
 * COMPLETED 隐藏输入栏、展示诊断结论/医嘱只读卡。轮询 onHide/onUnload 必清理。
 */
Page({
  data: {
    id: null,
    loading: true,
    consultation: null,
    progressStep: null,
    inProgress: false,
    completed: false,
    terminal: false, // CANCELLED/EXPIRED 边缘态（正常不会从入口进来）
    terminalHint: '',
    doctor: null,
    doctorInitial: '医',
    consultMethodLabel: '',
    isVideo: false,
    videoElapsed: '',
    methodInitiated: false,
    methodRequiredText: TEXTS.method_required,
    consultCompletedText: TEXTS.consult_completed,
    messages: [],
    inputValue: '',
    canSend: false,
    sending: false,
    anchorId: '',
  },

  _timer: null,
  _polling: false,
  _errorToasted: false,
  _lastMessageId: 0,

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
      .then((res) => {
        const consultation = res && res.consultation
        if (!consultation) return null
        // 尚未接诊（直接带 id 进入的边缘场景）：回等待页
        if (consultation.status === STATUSES.waiting_doctor) {
          this.stopPolling()
          my.redirectTo({ url: `/pages/consult/waiting/index?id=${consultation.id}` })
          return null
        }
        this.applyConsultation(consultation)
        return this.loadMessages()
      })
      .catch((err) => {
        // 首屏加载失败提示一次；轮询中的瞬时失败静默，下轮自愈
        if (this.data.loading && !this._errorToasted) {
          this._errorToasted = true
          my.showToast({ content: (err && err.detail) || '问诊加载失败', type: 'fail' })
        }
      })
      .then(() => {
        this._polling = false
      })
  },

  applyConsultation(consultation) {
    this._errorToasted = false
    const inProgress = consultation.status === STATUSES.in_progress
    const completed = consultation.status === STATUSES.completed
    const terminal = !inProgress && !completed
    const isVideo = consultation.consult_method === CONSULT_METHODS.video
    const doctor = consultation.doctor || null
    this.setData({
      loading: false,
      consultation,
      inProgress,
      completed,
      terminal,
      terminalHint: terminal ? terminalHintFor(consultation) : '',
      // progress_step 为契约 progress_steps key；终态为 null，五步中性展示
      progressStep: terminal
        ? null
        : consultation.progress_step || (completed ? 'COMPLETED' : 'IN_PROGRESS'),
      doctor,
      doctorInitial: doctor && doctor.name ? doctor.name.slice(0, 1) : '医',
      // 图文/视频都须由医生明确发起后才开放输入（server-java 同样 409 兜底）
      methodInitiated: consultation.consult_method != null,
      consultMethodLabel:
        consultation.consult_method_label ||
        CONSULT_METHOD_LABELS[consultation.consult_method] ||
        '',
      isVideo,
      videoElapsed: isVideo ? elapsedText(consultation.method_started_at) : '',
    })
    // 完成/终态后不再轮询（状态与消息均不再变化）
    if (!inProgress) this.stopPolling()
  },

  /** 增量拉取消息：after_id 取本地最后一条 id，返回升序直接追加。 */
  loadMessages() {
    return listMessages(this.data.id, this._lastMessageId)
      .then((res) => {
        const list = (res && res.messages) || []
        if (!list.length) return
        this._lastMessageId = list[list.length - 1].id
        this.setData({
          messages: [...this.data.messages, ...list.map((m) => this.decorateMessage(m))],
          anchorId: 'thread-bottom',
        })
      })
      .catch(() => {})
  },

  decorateMessage(m) {
    return {
      ...m,
      isPatient: m.sender_type === SENDER_TYPES.patient,
      isDoctor: m.sender_type === SENDER_TYPES.doctor,
      isSystem: m.sender_type === SENDER_TYPES.system,
    }
  },

  onInput(e) {
    const value = e.detail.value
    this.setData({ inputValue: value, canSend: value.trim().length > 0 })
  },

  send() {
    const content = this.data.inputValue.trim()
    if (!content || this.data.sending || !this.data.inProgress || !this.data.methodInitiated) return
    this.setData({ sending: true })
    sendMessage(this.data.id, content)
      .then((res) => {
        const message = res && res.message
        this.setData({ inputValue: '', canSend: false })
        // 发送成功即上屏；下一轮增量拉取从该 id 之后继续，不会重复
        if (message && message.id > this._lastMessageId) {
          this._lastMessageId = message.id
          this.setData({
            messages: [...this.data.messages, this.decorateMessage(message)],
            anchorId: 'thread-bottom',
          })
        }
      })
      .catch((err) =>
        my.showToast({ content: (err && err.detail) || '发送失败，请稍后重试', type: 'fail' })
      )
      .then(() => this.setData({ sending: false }))
  },
})
