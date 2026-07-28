const { ensureLogin } = require('../../utils/auth')
const { streamChat } = require('../../utils/chat-stream')

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

Page({
  data: {
    messages: [], // {id, role, kind, content, disclaimer, streaming}
    prompts: PROMPTS,
    inputValue: '',
    canSend: false,
    sending: false,
    gearIndex: 0,
    gearLabel: GEARS[0].label,
    conversationId: null,
    redFlag: null,
    anchorId: '',
  },

  _msgSeq: 0,
  _tokenQueue: [],
  _timer: null,

  onLoad() {
    ensureLogin().catch(() =>
      my.showToast({ content: '登录失败，请检查后端服务', type: 'fail' })
    )
  },

  onUnload() {
    this._stopTypewriter()
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

  sendText(content) {
    if (!content) return
    ensureLogin()
      .then(() => this.startRound(content))
      .catch(() => my.showToast({ content: '登录失败，请稍后重试', type: 'fail' }))
  },

  startRound(content) {
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

    streamChat({
      content,
      conversationId: this.data.conversationId,
      effort: GEARS[this.data.gearIndex].key,
      handlers: {
        onMeta: (data) => this.setData({ conversationId: data.conversation_id }),
        onAssistant: (data, tokens) => this.playAssistant(aiMsg.id, data, tokens),
        onRedFlag: (data) => this.showRedFlag(aiMsg.id, data),
        onDone: () => {},
        onError: (err) => this.failRound(aiMsg.id, err),
      },
    })
  },

  /** 打字机回放 token 流，放完后定格为完整内容并挂免责声明。 */
  playAssistant(id, data, tokens) {
    if (!tokens.length) {
      this.finishAssistant(id, data.content, data.disclaimer)
      return
    }
    this._tokenQueue = tokens.slice()
    this._final = data
    this._stopTypewriter()
    this._timer = setInterval(() => {
      const next = this._tokenQueue.shift()
      if (next === undefined) {
        this._stopTypewriter()
        this.finishAssistant(id, this._final.content, this._final.disclaimer)
        return
      }
      this.patchMessage(id, (msg) => ({ ...msg, content: msg.content + next }))
      this.setData({ anchorId: 'thread-bottom' })
    }, 50)
  },

  finishAssistant(id, content, disclaimer) {
    this.patchMessage(id, (msg) => ({ ...msg, content, disclaimer, streaming: false }))
    this.setData({ sending: false, canSend: this.data.inputValue.trim().length > 0 })
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
    this.setData({ redFlag: data, sending: false })
  },

  closeRedFlag() {
    this.setData({ redFlag: null })
  },

  failRound(id, err) {
    this._stopTypewriter()
    this.patchMessage(id, (msg) => ({
      ...msg,
      content: `抱歉，出了点问题：${err.message || '网络异常'}，请稍后重试`,
      streaming: false,
    }))
    this.setData({ sending: false })
  },

  patchMessage(id, patch) {
    this.setData({
      messages: this.data.messages.map((msg) => (msg.id === id ? patch(msg) : msg)),
    })
  },

  _stopTypewriter() {
    if (this._timer) {
      clearInterval(this._timer)
      this._timer = null
    }
  },
})
