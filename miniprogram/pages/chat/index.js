const { ensureLogin } = require('../../utils/auth')
const { createChatChannel } = require('../../utils/chat-stream')
const { SOOTHING_TEXTS } = require('../../utils/emotion')
const { drawerMethods } = require('./drawer')
const reportComposer = require('./report-composer')
const skinComposer = require('./skin-composer')
const dietComposer = require('./diet-composer')
const tongueComposer = require('./tongue-composer')
const pillboxComposer = require('./pillbox-composer')
const { hospitalRoutingMethods, scenarioFor } = require('./hospital-routing')
const { visibleBubbles } = require('./feature-bubbles')
const { currentProfile } = require('../../services/health-profiles')
const { featureGuideMethods } = require('./feature-guide')
const { isAsrEnabled, isTtsEnabled, recognizeSpeech, synthesizeSpeech } = require('../../utils/voice')
const { getCoords } = require('../../utils/location')
const { parseMarkdown } = require('../../utils/markdown')
const { defaultSelectedDate } = require('../../utils/department-slots')
const { createAssistantBubble, createAiBubbleState } = require('../../utils/ai-bubble-state')

// 推理档位三档循环（自动/快速回答/深度思考），后端映射为 reasoning_effort
const GEARS = [
  { key: 'auto', label: '自动' },
  { key: 'quick', label: '快速回答' },
  { key: 'deep', label: '深度思考' },
]

// 开场推荐提示词：导诊/用药注意/健康管理各一
const PROMPTS = [
  { text: '我头疼两天了，该挂什么科', icon: 'consult' },
  { text: '高血压老人用药应该注意什么', icon: 'capsule' },
  { text: '为我推荐 28 天健康减肥食谱计划', icon: 'diet' },
]

function conversationTitleFor(content) {
  const compact = String(content || '').replace(/\s+/g, ' ').trim()
  return compact ? compact.slice(0, 16) : ''
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
    // 空会话不预设业务场景标题；用户发出首条消息后再从内容生成标题。
    conversationTitle: '',
    redFlag: null,
    anchorId: '',
    // 对话记录抽屉
    drawerOpen: false,
    drawerLoading: false,
    conversations: [],
    pendingReport: null,
    reportProgress: '',
    pendingSkin: null,
    skinProgress: '',
    pendingDiet: null,
    dietProgress: '',
    pendingTongue: null,
    tongueProgress: '',
    pendingPillbox: null,
    pillboxProgress: '',
    profileLoaded: false,
    currentProfile: null,
    // 票 45：语音双向 UI 状态。asr/tts 入口可见性由契约开关控制（开通前隐藏，降级文字）。
    asrEnabled: isAsrEnabled(),
    ttsEnabled: isTtsEnabled(),
    recording: false, // 按住说话中
    voiceHint: '', // 录音/识别中提示
    voiceHintError: false, // 识别失败提示
    ttsLoadingId: 0, // 正在合成的 AI 气泡 id
    ttsPlayingId: 0, // 正在播放的 AI 气泡 id
  },

  _msgSeq: 0,
  _cardEnterSeq: 0,
  _recorder: null,
  _audioCtx: null,

  ...reportComposer,
  ...skinComposer,
  ...dietComposer,
  ...tongueComposer,
  ...pillboxComposer,
  ...hospitalRoutingMethods,
  ...featureGuideMethods,

  onLoad() {
    this._aiBubbleState = createAiBubbleState(this)
    // 冷启动 AI 页为全新聊天态，不自动恢复上次会话（见票 27 决策 13）
    ensureLogin()
      .then(() => this._ensureChatChannel())
      .catch(() => my.showToast({ content: '登录失败，请检查业务后端', type: 'fail' }))
  },

  /** 建通道并 eager connect；通道 close 后是一次性的（closed 不可逆），回显时必须重建。 */
  _ensureChatChannel() {
    if (this._chatChannel) return
    this._chatChannel = createChatChannel()
    this._chatChannel.connect().catch(() => {})
  },

  onShow() {
    // tab 页 onHide 已让出全局单实例连接，回显时重建通道
    this._ensureChatChannel()
    ensureLogin()
      .then(() => currentProfile())
      .then((result) => this.setData({ currentProfile: result.profile, profileLoaded: true }))
      .catch(() => this.setData({ currentProfile: null, profileLoaded: true }))
    // 消费 tab 外入口经 globalData 传入的上下文（switchTab 不能带参，票 42 阶段三）：
    // 报告解读入口页已完成分段上传的待解读请求、报告记录指定的待打开会话、
    // 首页「智能导诊」入口的导诊引导（票 62）、首页宫格「便捷购药」入口的购药引导（票 94）
    this.consumeReportEntry()
    this.consumeOpenConversation()
    this.consumeTriageEntry()
    this.consumeDrugGuideEntry()
  },

  // 点击对话中的图片消息全屏预览（ADR-0023 回拉链路）
  onPreviewImage(e) {
    const url = e.currentTarget.dataset.url
    if (!url) return
    my.previewImage({ urls: [url] })
  },

  // chat 是 tabBar 页，切走只触发 hide 不触发 unload：必须主动让出全局唯一的单实例
  // WebSocket（支付宝单实例二次 connectSocket 直接报错），否则预问诊等页面建连必失败；
  // close 会把在途轮次显式结算为中断，回显时由 onShow 重建通道。
  onHide() {
    if (this._chatChannel) {
      this._chatChannel.close()
      this._chatChannel = null
    }
  },

  onUnload() {
    this.clearVoiceTimers()
    if (this._aiBubbleState) this._aiBubbleState.dispose()
    if (this._chatChannel) this._chatChannel.close()
    this.stopTts()
  },

  onInput(e) {
    const value = e.detail.value
    if (this.data.voiceHint) this.clearVoiceTimers()
    this.setData({
      inputValue: value,
      canSend: value.trim().length > 0 && !this.data.sending,
      voiceHint: '',
      voiceHintError: false,
    })
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

  startRound(content, location, options) {
    const userMsg = { id: ++this._msgSeq, role: 'user', kind: 'text', content }
    const aiMsg = { ...createAssistantBubble(++this._msgSeq), entering: true }
    this._cardEnterSeq = 0
    this.setData({
      messages: [...this.data.messages, userMsg, aiMsg],
      conversationTitle: this.data.conversationTitle || conversationTitleFor(content),
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
      effort: GEARS[this.data.gearIndex].key,
      scenario: scenarioFor(content),
      longitude: location && location.longitude,
      latitude: location && location.latitude,
      retryStandardDepartmentId: options && options.retryStandardDepartmentId,
      prescriptionId: options && options.prescriptionId,
      handlers: {
        onMeta: (data) => {
          this.setData({ conversationId: data.conversation_id || this.data.conversationId })
          this._aiBubbleState.onMeta(aiMsg.id, data)
        },
        onFallback: () => this._aiBubbleState.onFallback(aiMsg.id),
        onThinking: (data) => this._aiBubbleState.onThinking(aiMsg.id, data),
        onToken: (data) => this.streamAssistantToken(aiMsg.id, data.text),
        onAssistant: (data) => this.finishAssistant(aiMsg.id, data),
        onToolStart: (data) => this._aiBubbleState.onToolStart(aiMsg.id, data),
        onToolEnd: (data) => this._aiBubbleState.onToolEnd(aiMsg.id, data),
        onDoctorRecommendations: (data) => this.appendCard('doctor_recommendations', data),
        onDoctorSlots: (data) => this.appendCard('doctor_slots', data),
        onHospitalRecommendations: (data) => this.appendCard('hospital_recommendations', data),
        onDepartmentSlots: (data) => this.onDepartmentSlots(data),
        onDepartmentOptions: (data) => this.appendCard('department_options', data),
        onAppointment: (data) => this.appendCard('appointment', data),
        onAppointments: (data) => this.appendCard('appointments', data),
        // 票 88：drug_order_confirm 语义改为「购药预览卡」，点击只跳统一购药确认页，
        // 不在聊天内提交订单；server-py 经 tool_to_event 下发 drug_order_prepare 事件（实时流），
        // 历史回放可能以 drug_order_confirm kind 落库；两 kind 渲染同一组件。
        onDrugOrderConfirmCard: (data) => this.appendCard('drug_order_confirm', data),
        onDrugOrderPrepare: (data) => this.appendCard('drug_order_prepare', data),
        // 票 80：处方选择卡（多处方点选），payload 含 prescriptions 列表
        onPrescriptions: (data) => this.appendCard('prescriptions', data),
        // 查药结果卡（search_medications 工具产出），只读展示药名/规格/单价/库存，不交互
        onMedications: (data) => this.appendCard('medications', data),
        onRedFlag: (data) => this.showRedFlag(aiMsg.id, data),
        onDone: () => this.completeRound(aiMsg.id),
        onError: (err) => this.failRound(aiMsg.id, err),
      },
    })
  },

  /** 重置聊天空态：messages/conversationId，供「新对话」与删除当前会话复用（决策 6/13）。 */
  resetChatState() {
    this.stopTts()
    this.clearVoiceTimers()
    this._cardEnterSeq = 0
    this.setData({
      messages: [],
      conversationId: null,
      conversationTitle: '',
      inputValue: '',
      canSend: false,
      sending: false,
      redFlag: null,
      recording: false,
      voiceHint: '',
      voiceHintError: false,
      pendingReport: null,
      reportProgress: '',
      pendingSkin: null,
      skinProgress: '',
      pendingDiet: null,
      dietProgress: '',
      pendingTongue: null,
      tongueProgress: '',
    })
    if (this._aiBubbleState) this._aiBubbleState.dispose()
    this._aiBubbleState = createAiBubbleState(this)
  },

  streamAssistantToken(id, text) {
    // 票 52：流式每次追加同步重算 Markdown 块；原文 content 保留给 TTS 与复制
    this.patchMessage(id, (msg) => {
      const ready = this._aiBubbleState.onBodyStart(id, msg)
      const content = ready.content + text
      return { ...ready, content, blocks: parseMarkdown(content) }
    })
    this.setData({ anchorId: 'thread-bottom' })
  },

  finishAssistant(id, data) {
    // 票 44：emotion 驱动气泡配色与安抚语；soothing_text 仅 anxious/fearful 携带（calm 无）。
    // 安抚语附气泡底部 disclaimer 上方，与回复共用 disclaimer，不单独标注、不进 messages 数组。
    const patch = (msg) => {
      const ready = this._aiBubbleState.onBodyStart(id, msg)
      return {
        ...ready,
        content: data.content,
        blocks: parseMarkdown(data.content),
        disclaimer: data.disclaimer,
        emotion: data.emotion || 'calm',
        soothingText: data.soothing_text || '',
        streaming: false,
      }
    }
    this.patchMessage(id, patch)
  },

  completeRound(id) {
    if (this._chatChannel) this._chatChannel.finishRound()
    if (id) this._aiBubbleState.complete(id)
    this.setData({
      sending: false,
      canSend: this.data.inputValue.trim().length > 0,
    })
  },

  appendCard(kind, card) {
    const message = {
      id: ++this._msgSeq,
      role: 'assistant',
      kind,
      card,
      disclaimer: card.disclaimer,
      entering: true,
      enterDelay: (this._cardEnterSeq++ % 4) * 40,
    }
    this.setData({ messages: [...this.data.messages, message], anchorId: 'thread-bottom' })
  },

  // ===== 票 50：智能导诊科室号源卡（department_slots）=====
  /** 成功卡补默认选中日期后追加；失败卡原样渲染（无 days/doctors）。 */
  onDepartmentSlots(data) {
    const card =
      data && data.status === 'ok' ? { ...data, selectedDate: defaultSelectedDate(data) } : data
    this.appendCard('department_slots', card)
  },

  /** 失败卡「重新查询」：以用户文案发起新一轮对话，携带科室 id 供后端跳过解析直查。 */
  onRetryDepartmentSlots(e) {
    if (this.data.sending) return
    const msg = this.data.messages.find((m) => String(m.id) === String(e.currentTarget.dataset.id))
    const departmentId =
      msg && msg.card && msg.card.standard_department && msg.card.standard_department.id
    if (!departmentId) return
    ensureLogin()
      .then(() => {
        const coords = getCoords()
        this.startRound(
          // 镜像 contracts/guided-registration.json retry_user_text（端侧无法读契约 JSON）
          '重新查询号源',
          { longitude: coords.lng, latitude: coords.lat },
          { retryStandardDepartmentId: departmentId }
        )
      })
      .catch(() => my.showToast({ content: '登录失败，请稍后重试', type: 'fail' }))
  },

  /** 票 65 科室选择卡点选：携带所选科室 id 走 retry 直查通道出号源卡（可重复点，只读幂等）。 */
  onDepartmentOptionTap(e) {
    if (this.data.sending) return
    const { id, name } = e.currentTarget.dataset
    if (!id) return
    ensureLogin()
      .then(() => {
        const coords = getCoords()
        this.startRound(
          // 镜像 contracts/guided-registration.json options_select_user_text（端侧无法读契约 JSON）
          `我选择${name || ''}`,
          { longitude: coords.lng, latitude: coords.lat },
          { retryStandardDepartmentId: id }
        )
      })
      .catch(() => my.showToast({ content: '登录失败，请稍后重试', type: 'fail' }))
  },

  /** 票 80 处方选择卡点选：携带所选 prescription_id 发起对话轮，server-py Agent 据此
   *  直接调 prepare_drug_order(prescription_id=...) 装配 drug_order_prepare 确认卡。 */
  onPrescriptionSelected({ cardId, prescriptionId }) {
    if (this.data.sending) return
    if (!prescriptionId) return
    ensureLogin()
      .then(() => {
        const coords = getCoords()
        this.startRound(
          '按此处方买药',
          { longitude: coords.lng, latitude: coords.lat },
          { prescriptionId }
        )
      })
      .catch(() => my.showToast({ content: '登录失败，请稍后重试', type: 'fail' }))
  },

  /** 日期条切换：卡片为受控组件，按 cardId 定位消息更新 card.selectedDate。 */
  onDepartmentSlotsSelectDate(date, cardId) {
    if (!date) return
    this.setData({
      messages: this.data.messages.map((msg) =>
        String(msg.id) === String(cardId)
          ? { ...msg, card: { ...msg.card, selectedDate: date } }
          : msg
      ),
    })
  },

  /** 预约跳转：与自助号源页（pages/booking/department-slots）同一确认页参数。 */
  onDepartmentSlotsBook({ scheduleId, doctor, slot, cardId }) {
    if (!slot || Number(slot.remaining_slots) <= 0) return
    const msg = this.data.messages.find((m) => String(m.id) === String(cardId))
    const departmentName =
      (msg && msg.card && msg.card.standard_department && msg.card.standard_department.name) || ''
    const hospitalName = doctor.campus_name
      ? `${doctor.hospital_name} · ${doctor.campus_name}`
      : doctor.hospital_name
    my.navigateTo({
      url:
        `/pages/booking/confirm/index?scheduleId=${scheduleId}` +
        `&scheduleDate=${encodeURIComponent(slot.schedule_date)}` +
        `&timeSlot=${encodeURIComponent(slot.time_slot)}` +
        `&doctorName=${encodeURIComponent(doctor.doctor_name)}` +
        `&departmentName=${encodeURIComponent(departmentName)}` +
        `&hospitalName=${encodeURIComponent(hospitalName)}` +
        `&fee=${encodeURIComponent(doctor.registration_fee)}`,
    })
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

  // ===== 票 88：购药预览卡/结果卡跳转（确认页实时校验后才建单）=====
  /** 预览卡点击：按 content 的 source/prescription_id/items 跳统一购药确认页；
   *  价格库存以确认页实时校验为准，历史会话回放同样可点击。 */
  openDrugOrderConfirm({ card }) {
    if (!card) return
    if (card.source === 'PRESCRIPTION' && card.prescription_id) {
      my.navigateTo({
        url: `/pages/drug-order-confirm/index?source=PRESCRIPTION&prescription_id=${card.prescription_id}`,
      })
      return
    }
    // OTC 明细经 URL 携带仅作名称/规格展示；下单参数与价格库存以确认页实时拉取为准
    const items = encodeURIComponent(JSON.stringify(card.items || []))
    my.navigateTo({ url: `/pages/drug-order-confirm/index?source=OTC&items=${items}` })
  },

  /** 结果卡「查看订单详情」：跳订单详情页管理支付/取消/履约进度（实时拉取）。 */
  openDrugOrderDetail({ orderId }) {
    if (!orderId) return
    my.navigateTo({ url: `/pages/drug-order-detail/index?id=${orderId}` })
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

  // ===== 票 45：语音双向（ASR 按住说话 + TTS 按需播报）=====
  // 按住说话：my.getRecorderManager 录音 -> POST /c/asr -> 识别文字填输入框（可见可改不自动发）。
  // 未配置/超时/失败三情况降级文字，不阻塞演示（语音入口在 asrEnabled=false 时根本不渲染）。
  // 监听器只在首次创建 recorder 时注册一次（getRecorderManager 返回单例，
  // 每次 start 重复注册 onStop 会导致 N 次按下后触发 N 个并行识别回调）。
  clearVoiceTimers() {
    clearTimeout(this._voiceWatchdog)
    clearTimeout(this._asrWatchdog)
    clearTimeout(this._voiceHintTimer)
  },

  clearVoiceHint() {
    this.clearVoiceTimers()
    this.setData({ voiceHint: '', voiceHintError: false })
  },

  showVoiceError(message) {
    this.clearVoiceTimers()
    this.setData({ recording: false, voiceHint: message, voiceHintError: true })
    // 失败提示是输入框内瞬态 placeholder；定时清除，避免非语音场景残留。
    this._voiceHintTimer = setTimeout(() => {
      this.setData({ voiceHint: '', voiceHintError: false })
    }, 4000)
  },

  ensureRecorder() {
    if (this._recorder) return
    this._recorder = my.getRecorderManager()
    this._recorder.onStop((res) => {
      clearTimeout(this._voiceWatchdog)
      this.setData({ recording: false, voiceHint: '识别中…', voiceHintError: false })
      // 取消（手指划出）不识别：onVoiceTouchCancel 置位 _voiceCancelled 后 stop
      if (this._voiceCancelled) {
        this._voiceCancelled = false
        this.clearVoiceHint()
        return
      }
      this._asrWatchdog = setTimeout(() => {
        this.showVoiceError('识别超时，请直接打字')
      }, 15000)
      recognizeSpeech({ filePath: res.tempFilePath })
        .then((result) => {
          clearTimeout(this._asrWatchdog)
          // 识别结果填输入框，可见可改、不自动发（ASR 可能有错字，对话流入口统一走 startRound）
          this.setData({
            inputValue: result.text || '',
            canSend: (result.text || '').trim().length > 0,
            voiceHint: '',
            voiceHintError: false,
          })
        })
        .catch(() => {
          this.showVoiceError('语音识别失败，请直接打字')
        })
    })
    this._recorder.onError(() => {
      this.showVoiceError('录音失败，请直接打字')
    })
  },

  onVoiceTouchStart() {
    if (!this.data.asrEnabled || this.data.sending || this.data.recording) return
    this.ensureRecorder()
    this.clearVoiceTimers()
    this._voiceCancelled = false
    this._recorder.start({ duration: 60000, sampleRate: 16000, numberOfChannels: 1, format: 'wav' })
    this.setData({ recording: true, voiceHint: '', voiceHintError: false })
  },

  onVoiceTouchEnd() {
    if (!this.data.recording) return
    this.setData({ recording: false, voiceHint: '识别中…', voiceHintError: false })
    // IDE 模拟器不支持录音 API（支付宝官方文档：以真机为准），onStop 可能永不触发；
    // 看门狗兜底避免“识别中”卡死，真机上 onStop 到达即清除本定时器
    this._voiceWatchdog = setTimeout(() => {
      this.showVoiceError('当前环境不支持录音，请用真机调试或直接打字')
    }, 15000)
    if (this._recorder) this._recorder.stop()
  },

  onVoiceTouchCancel() {
    // 手指划出输入区取消：停止录音但不识别（与常见按住说话交互一致）
    if (!this.data.recording) return
    this._voiceCancelled = true
    this.clearVoiceHint()
    this.setData({ recording: false })
    if (this._recorder) this._recorder.stop()
  },

  // AI 气泡播放/停止：按需点击触发（不自动播放，医疗场景打扰、公共场合、按需省调用）。
  // 整条回复一次合成（MVP 简单），my.createInnerAudioContext 播放/停止。
  onPlayTts(e) {
    if (!this.data.ttsEnabled) return
    const id = e.currentTarget.dataset.id
    const msg = this.data.messages.find((m) => m.id === id)
    if (!msg || !msg.content) return
    // 正在播放同一条：停止
    if (this.data.ttsPlayingId === id) {
      this.stopTts()
      return
    }
    this.stopTts()
    this.setData({ ttsLoadingId: id })
    synthesizeSpeech({ text: msg.content })
      .then((apFilePath) => {
        const ctx = my.createInnerAudioContext()
        ctx.src = apFilePath
        ctx.onEnded(() => this.setData({ ttsPlayingId: 0 }))
        ctx.onError(() => this.setData({ ttsLoadingId: 0, ttsPlayingId: 0 }))
        ctx.play()
        this._audioCtx = ctx
        this.setData({ ttsLoadingId: 0, ttsPlayingId: id })
      })
      .catch(() => {
        this.setData({ ttsLoadingId: 0 })
        my.showToast({ content: '语音播报暂不可用', type: 'fail' })
      })
  },

  stopTts() {
    if (this._audioCtx) {
      try {
        this._audioCtx.stop()
        this._audioCtx.destroy()
      } catch (_) {
        // 忽略已销毁上下文
      }
      this._audioCtx = null
    }
    this.setData({ ttsPlayingId: 0, ttsLoadingId: 0 })
  },

  patchMessage(id, patch) {
    this.setData({
      messages: this.data.messages.map((msg) => (msg.id === id ? patch(msg) : msg)),
    })
  },

  onToggleThinking(e) {
    this._aiBubbleState.toggle(e.currentTarget.dataset.id)
  },

  ...drawerMethods,
})
