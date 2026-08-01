const { apiBaseUrl } = require('./config')
const { getToken } = require('./auth')

function websocketUrl() {
  return `${apiBaseUrl.replace(/^http/, 'ws')}/c/chat/ws`
}

/** 页面级实时通道：一页一条 WebSocket；建连失败后同 request_id 降级到 SSE。 */
function createChatChannel() {
  let open = false
  let closed = false
  let connectPromise = null
  let connectResolve = null
  let connectReject = null
  let current = null
  let websocketUnavailable = false

  const onOpen = () => {
    open = true
    if (connectResolve) connectResolve()
    connectResolve = null
    connectReject = null
  }
  const onMessage = (res) => {
    let envelope
    try {
      envelope = JSON.parse(String(res.data || ''))
    } catch (err) {
      failCurrent(new Error('实时消息格式无效'))
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
    open = false
    websocketUnavailable = true
    console.warn('WebSocket 建连失败', detail)
    if (connectReject) connectReject(new Error(socketErrorMessage(detail)))
    connectResolve = null
    connectReject = null
    if (current) fallbackCurrent()
  }
  const onClose = () => {
    open = false
    if (!closed && current) fallbackCurrent()
  }

  my.onSocketOpen(onOpen)
  my.onSocketMessage(onMessage)
  my.onSocketError(onError)
  my.onSocketClose(onClose)

  function connect() {
    if (websocketUnavailable) return Promise.reject(new Error('WebSocket 建连失败'))
    if (open) return Promise.resolve()
    if (connectPromise) return connectPromise
    connectPromise = new Promise((resolve, reject) => {
      connectResolve = resolve
      connectReject = reject
      my.connectSocket({
        url: websocketUrl(),
        // 票 34 设计：握手仅经 Authorization 头携带患者 JWT（禁入 URL）；
        // devtools 会给 header 值包一层双引号，server-java 已兼容剥离。
        header: { Authorization: `Bearer ${getToken()}` },
        fail: () => reject(new Error('WebSocket 建连失败')),
      })
    }).catch((error) => {
      websocketUnavailable = true
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
    streamSse(current)
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
    conversation_id: params.conversationId || undefined,
    effort: params.effort,
    scenario: params.scenario,
    longitude: params.longitude,
    latitude: params.latitude,
  }
}

function streamSse(params) {
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
        parseSse(String(res.data || '')).forEach(({ event, data }) =>
          dispatchEvent(event, data, params.handlers)
        )
      } catch (err) {
        params.handlers.onError(err)
      }
    },
    fail: () => params.handlers.onError(new Error('无法连接服务器')),
  })
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
  else if (event === 'token') handlers.onToken(data)
  else if (event === 'message') handlers.onAssistant(data)
  else if (event === 'doctor_recommendations') handlers.onDoctorRecommendations(data)
  else if (event === 'doctor_slots') handlers.onDoctorSlots(data)
  else if (event === 'hospital_recommendations') handlers.onHospitalRecommendations(data)
  else if (event === 'appointment') handlers.onAppointment(data)
  else if (event === 'appointments') handlers.onAppointments(data)
  else if (event === 'red_flag') handlers.onRedFlag(data)
  else if (event === 'done') handlers.onDone()
}

module.exports = { createChatChannel }
