const {
  getConsultation,
  listMessages,
  sendMessage,
  uploadPhoto,
} = require('../../../services/consultation')
const {
  STATUSES,
  CONSULT_METHODS,
  CONSULT_METHOD_LABELS,
  SENDER_TYPES,
  MESSAGE_KINDS,
  TEXTS,
  terminalHintFor,
} = require('../../../utils/consultation')
const { isAsrEnabled, recognizeSpeech } = require('../../../utils/voice')
const { apiBaseUrl } = require('../../../utils/config')

const CONSENT_KEY = 'consult_photo_consent_v1'

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
    sendingImage: false,
    // 票 58：语音输入（ASR→文字回填输入框）；入口在 asr_enabled=false 时不渲染
    asrEnabled: isAsrEnabled(),
    recording: false,
    voiceHint: '',
    voiceHintError: false,
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
    const decorated = {
      ...m,
      isPatient: m.sender_type === SENDER_TYPES.patient,
      isDoctor: m.sender_type === SENDER_TYPES.doctor,
      isSystem: m.sender_type === SENDER_TYPES.system,
      isImage: m.kind === MESSAGE_KINDS.image,
      imageUrl: '',
    }
    // image 消息（票 58）：content 是 {"object_key","media_type"} JSON，按 key 回拉
    // MinIO 原图（/c/photos 代理端点，key 即凭证，与 chat 页 image 气泡同构）。
    if (decorated.isImage) {
      try {
        const image = JSON.parse(m.content)
        if (image.object_key) {
          decorated.imageUrl = `${apiBaseUrl}/c/photos?key=${encodeURIComponent(image.object_key)}`
        }
      } catch (e) {
        // 解析失败 imageUrl 留空，模板走文字兜底
      }
    }
    return decorated
  },

  onInput(e) {
    const value = e.detail.value
    this.setData({ inputValue: value, canSend: value.trim().length > 0 })
  },

  // ===== 票 58：患者发图（每次一张，MinIO 旁路持久化，医生只读查看）=====
  // 复用 AI 对话模块的选图/压缩/知情同意模式：首次使用弹知情同意并记 storage，
  // 相机与相册均单张；压缩后上传，成功即上屏（与 send 同一追加逻辑，轮询按 id 去重）。

  confirmPhotoConsent() {
    const accepted = my.getStorageSync({ key: CONSENT_KEY }).data
    if (accepted) return Promise.resolve()
    return new Promise((resolve, reject) => {
      my.confirm({
        title: '发送问诊图片',
        content: '请确认你有权上传该照片。照片仅用于本次问诊的医生查看，将留存于问诊记录中供医患双方回看。',
        confirmButtonText: '同意并发送',
        cancelButtonText: '取消',
        success: (result) => {
          if (!result.confirm) return reject(new Error('已取消'))
          my.setStorageSync({ key: CONSENT_KEY, data: true })
          resolve()
        },
      })
    })
  },

  onChooseImage() {
    if (this.data.sendingImage || !this.data.inProgress || !this.data.methodInitiated) return
    this.confirmPhotoConsent()
      .then(() => {
        my.chooseImage({
          count: 1,
          success: (result) => {
            const paths = result.apFilePaths || result.tempFilePaths || []
            if (!paths.length) return
            my.compressImage({
              apFilePaths: paths,
              compressLevel: 2,
              success: (compressed) => {
                const compressedPaths = compressed.apFilePaths || []
                if (!compressedPaths.length) {
                  my.showToast({ content: '图片压缩失败，请重新选择', type: 'fail' })
                  return
                }
                this.sendImage(compressedPaths[0])
              },
              fail: () => my.showToast({ content: '图片压缩失败，请重新选择', type: 'fail' }),
            })
          },
        })
      })
      .catch(() => {})
  },

  sendImage(filePath) {
    this.setData({ sendingImage: true })
    uploadPhoto(this.data.id, filePath)
      .then((res) => {
        const message = res && res.message
        if (message && message.id > this._lastMessageId) {
          this._lastMessageId = message.id
          this.setData({
            messages: [...this.data.messages, this.decorateMessage(message)],
            anchorId: 'thread-bottom',
          })
        }
      })
      .catch((err) =>
        my.showToast({ content: (err && err.detail) || '图片发送失败，请稍后重试', type: 'fail' })
      )
      .then(() => this.setData({ sendingImage: false }))
  },

  onPreviewImage(e) {
    const url = e.currentTarget.dataset.url
    if (url) my.previewImage({ urls: [url] })
  },

  // ===== 票 58：语音输入（按住说话 → ASR → 文字回填输入框，可编辑后发送）=====
  // 与 chat 页同构：getRecorderManager 单例只注册一次监听；取消（划出）不识别；
  // 识别失败降级提示直接打字。语音不构成消息类型，问诊记录只落文字。
  ensureRecorder() {
    if (this._recorder) return
    this._recorder = my.getRecorderManager()
    this._recorder.onStop((res) => {
      this.setData({ recording: false, voiceHint: '识别中…', voiceHintError: false })
      if (this._voiceCancelled) {
        this._voiceCancelled = false
        this.setData({ voiceHint: '' })
        return
      }
      recognizeSpeech({ filePath: res.tempFilePath })
        .then((result) => {
          this.setData({
            inputValue: result.text || '',
            canSend: (result.text || '').trim().length > 0,
            voiceHint: '',
            voiceHintError: false,
          })
        })
        .catch(() => {
          this.setData({ voiceHint: '语音识别失败，请直接打字', voiceHintError: true })
        })
    })
    this._recorder.onError(() => {
      this.setData({ recording: false, voiceHint: '录音失败，请直接打字', voiceHintError: true })
    })
  },

  onVoiceTouchStart() {
    if (!this.data.asrEnabled || this.data.sending || this.data.recording) return
    this.ensureRecorder()
    this._voiceCancelled = false
    this._recorder.start({ duration: 60000, sampleRate: 16000, numberOfChannels: 1, format: 'wav' })
    this.setData({ recording: true, voiceHint: '松开发送识别', voiceHintError: false })
  },

  onVoiceTouchEnd() {
    if (!this.data.recording) return
    this.setData({ recording: false, voiceHint: '识别中…', voiceHintError: false })
    if (this._recorder) this._recorder.stop()
  },

  onVoiceTouchCancel() {
    if (!this.data.recording) return
    this._voiceCancelled = true
    this.setData({ recording: false, voiceHint: '' })
    if (this._recorder) this._recorder.stop()
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
