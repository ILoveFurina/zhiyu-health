const { ensureLogin } = require('../../../utils/auth')
const { createChatChannel } = require('../../../utils/chat-stream')
const { getDraft } = require('../../../services/consultation')
const { listMessages } = require('../../../services/conversations')
const { parseMarkdown } = require('../../../utils/markdown')
const { DRAFT_STATUSES } = require('../../../utils/consultation')

// 预问诊对话的推理档位固定默认档；scenario 不传，由 server-java 校验草稿后强制预问诊场景
const DEFAULT_EFFORT = 'auto'

// 预问诊场景不产生工具/卡片事件，但 chat-stream 对所有事件类型无条件回调，必须占位
const NOOP = () => {}

/**
 * AI 预问诊对话页（票 55）：复用 pages/chat 的 WS/SSE 通道与气泡渲染，
 * 每轮对话携带 preconsultation_draft_id；message 事件后回拉草稿，
 * 摘要快照就绪后亮底部「查看病情摘要并确认」CTA。
 */
Page({
  data: {
    draftId: null,
    profileName: '', // 就诊人（锁定档案，由入口页传入；锁定语义在 server-java）
    progressStep: 'PRECONSULTATION',
    messages: [],
    inputValue: '',
    canSend: false,
    sending: false,
    conversationId: null,
    redFlag: null,
    anchorId: '',
    summaryReady: false, // 草稿已有摘要快照，显示底部 CTA
    historyLoaded: false,
  },

  _msgSeq: 0,

  onLoad(query) {
    this.setData({
      draftId: query && query.draftId,
      profileName: decodeURIComponent((query && query.profileName) || ''),
    })
    ensureLogin()
      .then(() => {
        this._chatChannel = createChatChannel()
        this._chatChannel.connect().catch(() => {})
      })
      .catch(() => my.showToast({ content: '登录失败，请检查业务后端', type: 'fail' }))
  },

  onShow() {
    // 从摘要页「继续调整」返回时刷新草稿，保持 CTA 与提交态同步
    if (this.data.draftId) this.loadDraft()
  },

  onUnload() {
    if (this._chatChannel) this._chatChannel.close()
  },

  loadDraft() {
    return getDraft(this.data.draftId)
      .then((res) => {
        const draft = res && res.draft
        if (!draft) throw new Error('预问诊草稿不存在')
        // 已提交草稿不可再聊：直接去关联问诊单（等待页自持状态分流）
        if (draft.status === DRAFT_STATUSES.submitted && draft.current_consultation_id) {
          my.redirectTo({ url: `/pages/consult/waiting/index?id=${draft.current_consultation_id}` })
          return
        }
        this.setData({
          summaryReady: !!draft.summary,
          conversationId: draft.conversation_id || this.data.conversationId,
        })
        // 恢复历史会话只回放一次，避免覆盖正在进行的对话
        if (draft.conversation_id && !this.data.historyLoaded) {
          this.loadHistory(draft.conversation_id)
        }
      })
      .catch((err) => {
        my.showToast({ content: (err && err.detail) || '预问诊草稿加载失败', type: 'fail' })
      })
  },

  /** 草稿已有关联会话时全量回放消息（续聊场景；AI 文本定格，不打字机）。 */
  loadHistory(conversationId) {
    this.setData({ historyLoaded: true })
    listMessages(conversationId)
      .then((messages) => {
        this.setData({
          messages: (messages || []).map((m) => this.replayMessage(m)),
          anchorId: 'thread-bottom',
        })
      })
      .catch(() => {})
  },

  replayMessage(m) {
    if (m.kind === 'red_flag') {
      return {
        id: ++this._msgSeq,
        role: 'assistant',
        kind: 'red_flag',
        content: m.content,
        streaming: false,
      }
    }
    return {
      id: ++this._msgSeq,
      role: m.role,
      kind: 'text',
      content: m.content,
      blocks: m.role === 'assistant' ? parseMarkdown(m.content) : undefined,
      disclaimer: m.disclaimer,
      streaming: false,
    }
  },

  onInput(e) {
    const value = e.detail.value
    this.setData({ inputValue: value, canSend: value.trim().length > 0 && !this.data.sending })
  },

  send() {
    if (!this.data.canSend || this.data.sending) return
    const content = this.data.inputValue.trim()
    const userMsg = { id: ++this._msgSeq, role: 'user', kind: 'text', content }
    const aiMsg = {
      id: ++this._msgSeq,
      role: 'assistant',
      kind: 'text',
      content: '',
      disclaimer: '',
      streaming: true,
    }
    this.setData({
      messages: [...this.data.messages, userMsg, aiMsg],
      inputValue: '',
      canSend: false,
      sending: true,
      anchorId: 'thread-bottom',
    })

    if (!this._chatChannel) this._chatChannel = createChatChannel()
    this._chatChannel.send({
      requestId: `${Date.now()}-${Math.random().toString(36).slice(2, 12)}`,
      content,
      conversationId: this.data.conversationId,
      effort: DEFAULT_EFFORT,
      preconsultationDraftId: this.data.draftId,
      handlers: {
        onMeta: (data) => this.setData({ conversationId: data.conversation_id }),
        onFallback: () => this.patchMessage(aiMsg.id, (msg) => ({ ...msg, content: '', blocks: [] })),
        onToken: (data) => this.streamToken(aiMsg.id, data.text),
        onAssistant: (data) => this.finishAssistant(aiMsg.id, data),
        onToolStart: NOOP,
        onToolEnd: NOOP,
        onDoctorRecommendations: NOOP,
        onDoctorSlots: NOOP,
        onHospitalRecommendations: NOOP,
        onDepartmentSlots: NOOP,
        onAppointment: NOOP,
        onAppointments: NOOP,
        onRedFlag: (data) => this.showRedFlag(aiMsg.id, data),
        onDone: () => this.completeRound(),
        onError: (err) => this.failRound(aiMsg.id, err),
      },
    })
  },

  streamToken(id, text) {
    this.patchMessage(id, (msg) => {
      const content = msg.content + text
      return { ...msg, content, blocks: parseMarkdown(content) }
    })
    this.setData({ anchorId: 'thread-bottom' })
  },

  finishAssistant(id, data) {
    this.patchMessage(id, (msg) => ({
      ...msg,
      content: data.content,
      blocks: parseMarkdown(data.content),
      disclaimer: data.disclaimer,
      streaming: false,
    }))
    // message 事件后草稿快照可能已更新：回拉，有摘要则亮底部 CTA
    this.loadDraft()
  },

  showRedFlag(id, data) {
    this.patchMessage(id, () => ({
      id,
      role: 'assistant',
      kind: 'red_flag',
      content: data.content,
      disclaimer: '',
      streaming: false,
    }))
    this.setData({ redFlag: data })
  },

  closeRedFlag() {
    this.setData({ redFlag: null })
  },

  completeRound() {
    if (this._chatChannel) this._chatChannel.finishRound()
    this.setData({ sending: false, canSend: this.data.inputValue.trim().length > 0 })
  },

  failRound(id, err) {
    this.patchMessage(id, (msg) => ({
      ...msg,
      content: `抱歉，出了点问题：${err.message || '网络异常'}，请稍后重试`,
      streaming: false,
    }))
    this.setData({ sending: false })
    if (this._chatChannel) this._chatChannel.finishRound()
  },

  patchMessage(id, patch) {
    this.setData({
      messages: this.data.messages.map((msg) => (msg.id === id ? patch(msg) : msg)),
    })
  },

  /** 底部 CTA：去摘要确认页（navigateTo，「继续调整」可 navigateBack 回来续聊）。 */
  openSummary() {
    my.navigateTo({ url: `/pages/consult/summary/index?draftId=${this.data.draftId}` })
  },
})
