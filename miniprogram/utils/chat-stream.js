const { apiBaseUrl } = require('./config')
const { ensureLogin, getToken } = require('./auth')

const WS_AUTH_TIMEOUT_MS = 5000

function websocketUrl() {
  return `${apiBaseUrl.replace(/^http/, 'ws')}/c/chat/ws`
}

/** 页面级实时通道：一页一条 WebSocket；建连失败后同 request_id 降级到 SSE。 */
function createChatChannel() {
  let open = false
  let authenticated = false
  let authTimer = null
  let closed = false
  let connectPromise = null
  let connectResolve = null
  let connectReject = null
  let current = null

  const onOpen = () => {
    open = true
    // 隧道可能重建 upgrade 并剥掉自定义 header；JWT 改在连接建立后的首帧传输，
    // authenticated 到达前 connect Promise 不完成，chat 不会抢在认证前发送。
    authTimer = setTimeout(() => {
      authTimer = null
      const shouldClose = open
      onError({ message: 'WebSocket 认证超时' })
      if (shouldClose) my.closeSocket()
    }, WS_AUTH_TIMEOUT_MS)
    my.sendSocketMessage({
      data: JSON.stringify({ type: 'auth', data: { token: getToken() } }),
      fail: (detail) => onError(detail),
    })
  }
  const onMessage = (res) => {
    let envelope
    try {
      envelope = JSON.parse(String(res.data || ''))
    } catch (err) {
      failCurrent(new Error('实时消息格式无效'))
      return
    }
    if (envelope.type === 'authenticated') {
      if (!open) return
      clearAuthTimer()
      authenticated = true
      if (connectResolve) connectResolve()
      connectResolve = null
      connectReject = null
      return
    }
    if (envelope.type === 'error' && !authenticated) {
      clearAuthTimer()
      const error = new Error((envelope.data && envelope.data.message) || 'WebSocket 认证失败')
      if (connectReject) connectReject(error)
      connectResolve = null
      connectReject = null
      if (open) my.closeSocket()
      if (current) fallbackCurrent()
      return
    }
    if (!current || envelope.request_id !== current.requestId) return
    if (envelope.type === 'accepted') current.handlers.onMeta(envelope.data)
    else if (envelope.type === 'event') dispatchEvent(envelope.event, envelope.data, current.handlers)
    else if (envelope.type === 'error') {
      failCurrent(new Error((envelope.data && envelope.data.message) || '对话处理失败'))
    }
  }
  const onError = (detail) => {
    clearAuthTimer()
    open = false
    authenticated = false
    console.warn('WebSocket 建连失败，本轮降级且下轮重试', detail)
    if (connectReject) connectReject(new Error(socketErrorMessage(detail)))
    connectResolve = null
    connectReject = null
    connectPromise = null
    if (current) fallbackCurrent()
  }
  const onClose = () => {
    clearAuthTimer()
    open = false
    authenticated = false
    if (connectReject) connectReject(new Error('WebSocket 认证未完成'))
    connectResolve = null
    connectReject = null
    connectPromise = null
    if (!closed && current) fallbackCurrent()
  }

  my.onSocketOpen(onOpen)
  my.onSocketMessage(onMessage)
  my.onSocketError(onError)
  my.onSocketClose(onClose)

  function clearAuthTimer() {
    if (authTimer !== null) clearTimeout(authTimer)
    authTimer = null
  }

  function connect() {
    if (open && authenticated) return Promise.resolve()
    if (connectPromise) return connectPromise
    // 先等登录态就绪再握手，避免启动期 token 尚未落 storage 导致握手 401
    connectPromise = ensureLogin()
      .then(() => {
        return new Promise((resolve, reject) => {
          connectResolve = resolve
          connectReject = reject
          my.connectSocket({
            url: websocketUrl(),
            // JWT 不进 URL/upgrade header；cpolar 重建握手后仍能透传连接内 auth 首帧。
            fail: () => reject(new Error('WebSocket 建连失败')),
          })
        })
      })
      .catch((error) => {
        connectPromise = null
        throw error
      })
    return connectPromise
  }

  function send(params) {
    if (current) {
      params.handlers.onError(new Error('当前对话轮次尚未完成'))
      return
    }
    current = { ...params, fallbackStarted: false }
    connect()
      .then(() => {
        my.sendSocketMessage({
          data: JSON.stringify({
            type: 'chat',
            request_id: params.requestId,
            data: requestData(params),
          }),
          fail: fallbackCurrent,
        })
      })
      .catch(fallbackCurrent)
  }

  function fallbackCurrent() {
    if (!current || current.fallbackStarted) return
    current.fallbackStarted = true
    if (current.handlers.onFallback) current.handlers.onFallback()
    // isAlive 闭包钉住本轮对象：响应迟到时不得更新已结束轮次或已卸载页面。
    const round = current
    streamSse(round, () => current === round)
  }

  function failCurrent(error) {
    if (!current) return
    const handlers = current.handlers
    current = null
    handlers.onError(error)
  }

  function finishRound() {
    current = null
  }

  function close() {
    closed = true
    current = null
    clearAuthTimer()
    if (open) my.closeSocket()
    if (my.offSocketOpen) my.offSocketOpen(onOpen)
    if (my.offSocketMessage) my.offSocketMessage(onMessage)
    if (my.offSocketError) my.offSocketError(onError)
    if (my.offSocketClose) my.offSocketClose(onClose)
  }

  return { connect, send, close, finishRound }
}

function socketErrorMessage(detail) {
  if (!detail || typeof detail !== 'object') return 'WebSocket 建连失败'
  return detail.errorMessage || detail.errMsg || detail.message || 'WebSocket 建连失败'
}

function requestData(params) {
  return {
    content: params.content,
    // 票 51：药品说明书流信封字段，与 content 互斥（拍药盒识别后自动携带）
    medication_name: params.medicationName || undefined,
    // 票 50：号源卡「重新查询」重试时携带，后端跳过科室解析直查
    retry_standard_department_id: params.retryStandardDepartmentId || undefined,
    // 票 55：预问诊对话绑定草稿 id，server-java 校验归属与状态后强制预问诊场景
    preconsultation_draft_id: params.preconsultationDraftId || undefined,
    // 票 80：处方选择卡点选回传的所选处方 id，server-py Agent 据此直接装配购药确认卡
    prescription_id: params.prescriptionId || undefined,
    conversation_id: params.conversationId || undefined,
    effort: params.effort,
    scenario: params.scenario,
    longitude: params.longitude,
    latitude: params.latitude,
  }
}

// my.request 只能在完整 SSE 响应结束后返回。降级时跳过已经失去实时语义的
// token/thinking 快照，只投影 meta、最终 message、卡片与 done：不伪造逐字节奏，
// 也不把完成后的 thinking 重放成“正在思考”或据此计算虚假耗时。
function dispatchSseSnapshot(events, handlers, isAlive) {
  for (const { event, data } of events) {
    if (!isAlive()) return
    if (event === 'token' || event === 'thinking') continue
    dispatchEvent(event, data, handlers)
  }
}

function streamSse(params, isAlive) {
  // 降级路径同样等登录就绪，保证 Authorization 带有效 token
  ensureLogin().then(() => {
    my.request({
      url: `${apiBaseUrl}/c/chat`,
      method: 'POST',
      dataType: 'text',
      responseType: 'text',
      timeout: 300000,
      data: { request_id: params.requestId, ...requestData(params) },
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${getToken()}`,
      },
      success: (res) => {
        if (res.status !== 200) {
          params.handlers.onError(new Error(`请求失败（${res.status}）`))
          return
        }
        try {
          dispatchSseSnapshot(parseSse(String(res.data || '')), params.handlers, isAlive)
        } catch (err) {
          params.handlers.onError(err)
        }
      },
      fail: () => params.handlers.onError(new Error('无法连接服务器')),
    })
  }).catch((err) => params.handlers.onError(err))
}

function parseSse(raw) {
  return raw
    .split('\n\n')
    .filter((frame) => frame.trim())
    .map((frame) => {
      const lines = frame.trim().split('\n')
      return {
        event: lines[0].replace(/^event:\s*/, ''),
        data: JSON.parse(lines[1].replace(/^data:\s*/, '')),
      }
    })
}

function dispatchEvent(event, data, handlers) {
  if (event === 'meta') handlers.onMeta(data)
  else if (event === 'thinking' && handlers.onThinking) handlers.onThinking(data)
  else if (event === 'token') handlers.onToken(data)
  else if (event === 'message') handlers.onAssistant(data)
  else if (event === 'tool_start') handlers.onToolStart(data)
  else if (event === 'tool_end') handlers.onToolEnd(data)
  else if (event === 'doctor_recommendations') handlers.onDoctorRecommendations(data)
  else if (event === 'doctor_slots') handlers.onDoctorSlots(data)
  else if (event === 'hospital_recommendations') handlers.onHospitalRecommendations(data)
  else if (event === 'department_slots') handlers.onDepartmentSlots(data)
  else if (event === 'department_options') handlers.onDepartmentOptions(data)
  else if (event === 'appointment') handlers.onAppointment(data)
  else if (event === 'appointments') handlers.onAppointments(data)
  else if (event === 'drug_order_prepare') handlers.onDrugOrderPrepare(data)
  else if (event === 'drug_order_confirm') handlers.onDrugOrderConfirmCard(data)
  else if (event === 'prescriptions') handlers.onPrescriptions(data)
  else if (event === 'red_flag') handlers.onRedFlag(data)
  else if (event === 'done') handlers.onDone()
}

module.exports = { createChatChannel }
