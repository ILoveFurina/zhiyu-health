const { apiBaseUrl } = require('./config')
const { getToken } = require('./auth')

/**
 * C 端对话 SSE 通道。
 *
 * 后端契约是 SSE（与后续工具调用可视化同通道）。支付宝 my.request
 * 没有 onChunkReceived 分片回调，无法边收边渲染，因此客户端完整读取
 * SSE 流后按事件序回放：token 交给页面打字机输出，其余事件即时分发。
 * 若未来支付宝支持分片回调，只需替换本文件内部实现。
 */
function streamChat({ content, conversationId, effort, scenario, handlers }) {
  my.request({
    url: `${apiBaseUrl}/c/chat`,
    method: 'POST',
    dataType: 'text', // SSE 非 JSON，防止按 JSON 解析报错
    responseType: 'text',
    timeout: 120000,
    data: {
      content,
      conversation_id: conversationId || undefined,
      effort,
      scenario,
    },
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${getToken()}`,
    },
    success: (res) => {
      if (res.status !== 200) {
        handlers.onError(new Error(`请求失败（${res.status}）`))
        return
      }
      try {
        dispatch(parseSse(String(res.data || '')), handlers)
      } catch (err) {
        handlers.onError(err)
      }
    },
    fail: () => handlers.onError(new Error('无法连接服务器')),
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

function dispatch(events, handlers) {
  let tokens = []
  for (const { event, data } of events) {
    if (event === 'meta') {
      handlers.onMeta(data)
    } else if (event === 'token') {
      tokens.push(data.text)
    } else if (event === 'message') {
      handlers.onAssistant(data, tokens)
      tokens = []
    } else if (event === 'doctor_recommendations') {
      handlers.onDoctorRecommendations(data)
    } else if (event === 'doctor_slots') {
      handlers.onDoctorSlots(data)
    } else if (event === 'red_flag') {
      handlers.onRedFlag(data)
      tokens = []
    } else if (event === 'done') {
      handlers.onDone()
    }
  }
}

module.exports = { streamChat }
