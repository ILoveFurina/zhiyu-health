const { ensureLogin } = require('../../../utils/auth')
const { createChatChannel } = require('../../../utils/chat-stream')
const { getDraft, abandonDraft } = require('../../../services/consultation')
const { listMessages } = require('../../../services/conversations')
const { parseMarkdown } = require('../../../utils/markdown')
const { DRAFT_STATUSES } = require('../../../utils/consultation')
const { createAssistantBubble, createAiBubbleState } = require('../../../utils/ai-bubble-state')

// 预问诊对话的推理档位固定默认档；scenario 不传，由 server-java 校验草稿后强制预问诊场景
const DEFAULT_EFFORT = 'auto'

// 预问诊场景不产生工具/卡片事件，但 chat-stream 对所有事件类型无条件回调，必须占位
const NOOP = () => {}

/**
 * AI 预问诊对话页（票 55）：复用 pages/chat 的 WS/SSE 通道与气泡渲染，
 * 每轮对话携带 preconsultation_draft_id；摘要不再随 message 事件下发（改为
 * server-py 后台异步整理并回调 server-java 落草稿），故 message 事件后启动
 * 轮询回拉草稿，摘要快照就绪后亮底部「查看病情摘要并确认」CTA。
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
  _unloaded: false, // 页面已卸载，停止摘要轮询
  _summaryPollTimers: [], // 摘要轮询定时器，卸载时统一清理

  onLoad(query) {
    this._aiBubbleState = createAiBubbleState(this)
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
    this._unloaded = true
    this._clearSummaryPoll()
    if (this._aiBubbleState) this._aiBubbleState.dispose()
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
          return false
        }
        this.setData({
          summaryReady: !!draft.summary,
          conversationId: draft.conversation_id || this.data.conversationId,
        })
        // 恢复历史会话只回放一次，避免覆盖正在进行的对话
        if (draft.conversation_id && !this.data.historyLoaded) {
          this.loadHistory(draft.conversation_id)
        }
        return !!draft.summary
      })
      .catch((err) => {
        my.showToast({ content: (err && err.detail) || '预问诊草稿加载失败', type: 'fail' })
        return false
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
      deepThoughtBadge: m.role === 'assistant' && m.effort === 'high',
      thinkingSummary: m.role === 'assistant' && m.effort === 'high' ? '已深度思考' : '',
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
    const aiMsg = createAssistantBubble(++this._msgSeq)
    this.setData({
      messages: [...this.data.messages, userMsg, aiMsg],
      inputValue: '',
      canSend: false,
      sending: true,
      anchorId: 'thread-bottom',
    })
    this._aiBubbleState.start(aiMsg.id)

    if (!this._chatChannel) this._chatChannel = createChatChannel()
    this._chatChannel.send({
      requestId: `${Date.now()}-${Math.random().toString(36).slice(2, 12)}`,
      content,
      conversationId: this.data.conversationId,
      effort: DEFAULT_EFFORT,
      preconsultationDraftId: this.data.draftId,
      handlers: {
        onMeta: (data) => {
          this.setData({ conversationId: data.conversation_id || this.data.conversationId })
          this._aiBubbleState.onMeta(aiMsg.id, data)
        },
        onFallback: () => this.patchMessage(aiMsg.id, (msg) => ({ ...msg, content: '', blocks: [] })),
        onThinking: (data) => this._aiBubbleState.onThinking(aiMsg.id, data),
        onToken: (data) => this.streamToken(aiMsg.id, data.text),
        onAssistant: (data) => this.finishAssistant(aiMsg.id, data),
        onToolStart: (data) => this._aiBubbleState.onToolStart(aiMsg.id, data),
        onToolEnd: (data) => this._aiBubbleState.onToolEnd(aiMsg.id, data),
        onDoctorRecommendations: NOOP,
        onDoctorSlots: NOOP,
        onHospitalRecommendations: NOOP,
        onDepartmentSlots: NOOP,
        onAppointment: NOOP,
        onAppointments: NOOP,
        onRedFlag: (data) => this.showRedFlag(aiMsg.id, data),
        onDone: () => this.completeRound(aiMsg.id),
        onError: (err) => this.failRound(aiMsg.id, err),
      },
    })
  },

  streamToken(id, text) {
    this.patchMessage(id, (msg) => {
      const ready = this._aiBubbleState.onBodyStart(id, msg)
      const content = ready.content + text
      return { ...ready, content, blocks: parseMarkdown(content) }
    })
    this.setData({ anchorId: 'thread-bottom' })
  },

  finishAssistant(id, data) {
    this.patchMessage(id, (msg) => ({
      ...this._aiBubbleState.onBodyStart(id, msg),
      content: data.content,
      blocks: parseMarkdown(data.content),
      disclaimer: data.disclaimer,
      streaming: false,
    }))
    // 摘要不再随 message 事件下发（server-py 后台异步整理并回调 server-java 落草稿），
    // 故 message 事件后启动轮询回拉草稿，摘要就绪即亮底部 CTA。
    this._pollSummary()
  },

  /**
   * 摘要轮询：3s/7s/12s 共 3 次，每次回拉草稿，summaryReady 即停。
   * 摘要判定是后台异步 LLM 调用（可能数秒~数十秒），轮询覆盖常见时延；
   * 用户从摘要页返回时 onShow 也会回拉兜底。卸载时统一清理定时器。
   */
  _pollSummary() {
    this._clearSummaryPoll()
    const delays = [3000, 4000, 5000]
    delays.forEach((delay) => {
      const timer = setTimeout(() => {
        if (this._unloaded || this.data.summaryReady) return
        this.loadDraft().then((ready) => {
          if (ready) this._clearSummaryPoll()
        })
      }, delay)
      this._summaryPollTimers.push(timer)
    })
  },

  _clearSummaryPoll() {
    if (this._summaryPollTimers.length) {
      this._summaryPollTimers.forEach((t) => clearTimeout(t))
      this._summaryPollTimers = []
    }
  },

  showRedFlag(id, data) {
    this._aiBubbleState.fail(id)
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

  completeRound(id) {
    if (this._chatChannel) this._chatChannel.finishRound()
    this._aiBubbleState.complete(id)
    this.setData({ sending: false, canSend: this.data.inputValue.trim().length > 0 })
  },

  failRound(id, err) {
    this._aiBubbleState.fail(id)
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

  onToggleThinking(e) {
    this._aiBubbleState.toggle(e.currentTarget.dataset.id)
  },

  /** 底部 CTA：去摘要确认页（navigateTo，「继续调整」可 navigateBack 回来续聊）。 */
  openSummary() {
    my.navigateTo({ url: `/pages/consult/summary/index?draftId=${this.data.draftId}` })
  },

  abandon() {
    my.confirm({
      title: '放弃本次预问诊',
      content: '对话记录会保留，但本次进度将从待办移除。确定放弃吗？',
      confirmButtonText: '确定放弃',
      success: (res) => {
        if (!res.confirm) return
        abandonDraft(this.data.draftId)
          .then(() => my.switchTab({ url: '/pages/home/index' }))
          .catch((err) => my.showToast({ content: (err && err.detail) || '操作失败', type: 'fail' }))
      },
    })
  },
})
