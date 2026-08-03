const { ensureLogin } = require('../../utils/auth')
const { createChatChannel } = require('../../utils/chat-stream')
const { drawerMethods } = require('./drawer')
const reportComposer = require('./report-composer')
const { hospitalRoutingMethods, scenarioFor } = require('./hospital-routing')
const { visibleBubbles } = require('./feature-bubbles')
const { currentProfile } = require('../../services/health-profiles')
const { featureGuideMethods } = require('./feature-guide')

// 推理档位三档循环（自动/快速回答/深度思考），后端映射为 reasoning_effort
const GEARS = [
  { key: 'auto', label: '自动' },
  { key: 'quick', label: '快速回答' },
  { key: 'deep', label: '深度思考' },
]

// 开场推荐提示词：导诊/用药注意/健康管理各一
const PROMPTS = [
  '我头疼两天了，该挂什么科',
  '高血压老人用药应该注意什么',
  '为我推荐 28 天健康减肥食谱计划',
]

// 工具名->中文文案映射（票 24）：状态条显示"正在{文案}…"，本地维护。
// 知识工具（search_knowledge/traverse_graph）不发 tool_start，故不在此映射。
const TOOL_LABELS = {
  recommend_doctors: '推荐医生',
  get_doctor_slots: '查询号源',
  find_hospitals: '查找医院',
  create_appointment: '挂号',
  get_appointment: '查询挂号',
}

Page({
  data: {
    messages: [], // 文本、红线警告或医生/号源结构化卡片
    prompts: PROMPTS,
    bubbles: visibleBubbles(), // 功能入口气泡（D5）：仅 enabled 项
    inputValue: '',
    canSend: false,
    sending: false,
    gearIndex: 0,
    gearLabel: GEARS[0].label,
    conversationId: null,
    redFlag: null,
    anchorId: '',
    // 工具进度状态条（瞬态，不进 messages 数组）：tool_start 显示"正在…"，tool_end 分流
    toolProgress: '',
    toolProgressError: false,
    // 对话记录抽屉
    drawerOpen: false,
    drawerLoading: false,
    conversations: [],
    pendingReport: null,
    reportProgress: '',
    profileLoaded: false,
    currentProfile: null,
  },

  _msgSeq: 0,

  ...reportComposer,
  ...hospitalRoutingMethods,
  ...featureGuideMethods,

  onLoad() {
    // 冷启动 AI 页为全新聊天态，不自动恢复上次会话（见票 27 决策 13）
    ensureLogin()
      .then(() => {
        this._chatChannel = createChatChannel()
        this._chatChannel.connect().catch(() => {})
      })
      .catch(() => my.showToast({ content: '登录失败，请检查业务后端', type: 'fail' }))
  },

  onShow() {
    ensureLogin()
      .then(() => currentProfile())
      .then((result) => this.setData({ currentProfile: result.profile, profileLoaded: true }))
      .catch(() => this.setData({ currentProfile: null, profileLoaded: true }))
    // 消费 tab 外入口经 globalData 传入的上下文（switchTab 不能带参，票 42 阶段三）：
    // 报告解读入口页已完成分段上传的待解读请求、报告记录指定的待打开会话
    this.consumeReportEntry()
    this.consumeOpenConversation()
  },

  onUnload() {
    if (this._chatChannel) this._chatChannel.close()
  },

  onInput(e) {
    const value = e.detail.value
    this.setData({ inputValue: value, canSend: value.trim().length > 0 && !this.data.sending })
  },

  cycleGear() {
    const gearIndex = (this.data.gearIndex + 1) % GEARS.length
    this.setData({ gearIndex, gearLabel: GEARS[gearIndex].label })
  },

  sendPrompt(e) {
    this.sendText(e.currentTarget.dataset.text)
  },

  send() {
    if (!this.data.canSend || this.data.sending) return
    this.sendText(this.data.inputValue.trim())
  },

  startRound(content, location) {
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
      effort: GEARS[this.data.gearIndex].key,
      scenario: scenarioFor(content),
      longitude: location && location.longitude,
      latitude: location && location.latitude,
      handlers: {
        onMeta: (data) => this.setData({ conversationId: data.conversation_id }),
        onFallback: () => this.patchMessage(aiMsg.id, (msg) => ({ ...msg, content: '' })),
        onToken: (data) => this.streamAssistantToken(aiMsg.id, data.text),
        onAssistant: (data) => this.finishAssistant(aiMsg.id, data.content, data.disclaimer),
        onToolStart: (data) => this.onToolStart(data),
        onToolEnd: (data) => this.onToolEnd(data),
        onDoctorRecommendations: (data) => this.appendCard('doctor_recommendations', data),
        onDoctorSlots: (data) => this.appendCard('doctor_slots', data),
        onHospitalRecommendations: (data) => this.appendCard('hospital_recommendations', data),
        onAppointment: (data) => this.appendCard('appointment', data),
        onAppointments: (data) => this.appendCard('appointments', data),
        onRedFlag: (data) => this.showRedFlag(aiMsg.id, data),
        onDone: () => this.completeRound(),
        onError: (err) => this.failRound(aiMsg.id, err),
      },
    })
  },

  /** 重置聊天空态：messages/conversationId，供「新对话」与删除当前会话复用（决策 6/13）。 */
  resetChatState() {
    this.setData({
      messages: [],
      conversationId: null,
      inputValue: '',
      canSend: false,
      sending: false,
      redFlag: null,
      toolProgress: '',
      toolProgressError: false,
    })
  },

  streamAssistantToken(id, text) {
    this.patchMessage(id, (msg) => ({ ...msg, content: msg.content + text }))
    this.setData({ anchorId: 'thread-bottom' })
  },

  finishAssistant(id, content, disclaimer) {
    this.patchMessage(id, (msg) => ({ ...msg, content, disclaimer, streaming: false }))
  },

  /** 工具进度状态条（票 24）：tool_start 显示"正在{文案}…"，瞬态不进 messages。 */
  onToolStart(data) {
    const label = TOOL_LABELS[data.tool_name] || data.tool_name || '处理'
    this.setData({ toolProgress: `正在${label}…`, toolProgressError: false })
  },

  /** tool_end 按结果分流：success 短暂显示后清空，error 显示失败，skipped 静默不显示。 */
  onToolEnd(data) {
    const result = data.result
    if (result === 'skipped') {
      // 降级对用户不可见：直接清空状态条（与"降级"词条一致）
      this.setData({ toolProgress: '', toolProgressError: false })
      return
    }
    if (result === 'error') {
      const label = TOOL_LABELS[data.tool_name] || data.tool_name || '操作'
      this.setData({ toolProgress: `${label}失败`, toolProgressError: true })
      return
    }
    // success：短暂保留后清空，避免与紧随的卡片消息视觉重复
    setTimeout(() => {
      if (this.data.toolProgress && !this.data.toolProgressError) {
        this.setData({ toolProgress: '' })
      }
    }, 800)
  },

  completeRound() {
    if (this._chatChannel) this._chatChannel.finishRound()
    this.setData({
      sending: false,
      canSend: this.data.inputValue.trim().length > 0,
      toolProgress: '',
      toolProgressError: false,
    })
  },

  appendCard(kind, card) {
    const message = {
      id: ++this._msgSeq,
      role: 'assistant',
      kind,
      card,
      disclaimer: card.disclaimer,
    }
    this.setData({ messages: [...this.data.messages, message], anchorId: 'thread-bottom' })
  },

  onDoctorSelected(selection) {
    const { doctorId, name } = selection
    this.sendText(`我想选择${name}医生（doctor_id: ${doctorId}），请查询可预约时段`)
  },

  onSlotSelected(selection) {
    const { scheduleId, scheduleDate, timeSlot } = selection
    this.sendText(
      `我选择 ${scheduleDate} ${timeSlot} 的号源（schedule_id: ${scheduleId}），请帮我完成挂号`
    )
  },

  openAppointments() {
    my.navigateTo({ url: '/pages/appointments/index' })
  },

  openHealthProfiles() {
    my.navigateTo({ url: '/pages/health/index' })
  },

  startHealthProfile() {
    my.navigateTo({ url: '/pages/health/index?create=1' })
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

  ...drawerMethods,
})
