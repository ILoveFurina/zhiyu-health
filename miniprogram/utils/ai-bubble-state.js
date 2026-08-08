const TOOL_LABELS = {
  recommend_doctors: '推荐医生',
  get_doctor_slots: '查询号源',
  find_hospitals: '查找医院',
  create_appointment: '挂号',
  get_appointment: '查询挂号',
}

const PROFILES = {
  conservative: {
    initial: '正在分析您的问题…',
    delayed: '问题有些复杂，正在为您仔细整理…',
    threshold: 5000,
  },
  quick: {
    initial: '正在回复…',
    delayed: '正在为您仔细整理回复…',
    threshold: 3000,
  },
  deep: {
    initial: '正在深度思考您的问题…',
    delayed: '问题有些复杂，正在为您仔细整理…',
    threshold: 9000,
  },
}

function profileFor(effort) {
  if (effort === 'disabled' || effort === 'quick') return PROFILES.quick
  if (effort === 'high' || effort === 'deep') return PROFILES.deep
  return PROFILES.conservative
}

function createAssistantBubble(id) {
  return {
    id,
    role: 'assistant',
    kind: 'text',
    content: '',
    disclaimer: '',
    emotion: 'calm',
    soothingText: '',
    streaming: true,
    waitingText: PROFILES.conservative.initial,
    toolStatus: '',
    toolStatusError: false,
    thinkingText: '',
    thinkingSummary: '',
    thinkingComplete: false,
    thinkingExpanded: false,
  }
}

/** chat 与预问诊共用的 AI 气泡瞬态控制器；所有思考内容只留在页面内存。 */
function createAiBubbleState(page) {
  const timers = new Map()
  const startedAt = new Map()
  const metaAt = new Map()

  function clearTimer(id) {
    const timer = timers.get(id)
    if (timer) clearTimeout(timer)
    timers.delete(id)
  }

  function scheduleDelayedText(id, effort) {
    clearTimer(id)
    const profile = profileFor(effort)
    const elapsed = Date.now() - (startedAt.get(id) || Date.now())
    const delay = Math.max(0, profile.threshold - elapsed)
    const timer = setTimeout(() => {
      timers.delete(id)
      page.patchMessage(id, (msg) => {
        if (!msg.streaming || msg.content || msg.toolStatus) return msg
        return { ...msg, waitingText: profile.delayed }
      })
    }, delay)
    timers.set(id, timer)
  }

  function finishThinking(id, msg) {
    if (!msg.thinkingText || msg.thinkingComplete) return msg
    const origin = metaAt.get(id) || startedAt.get(id) || Date.now()
    const seconds = Math.max(1, Math.round((Date.now() - origin) / 1000))
    return {
      ...msg,
      thinkingComplete: true,
      thinkingExpanded: false,
      thinkingSummary: `已深度思考（用时 ${seconds} 秒）`,
    }
  }

  return {
    start(id) {
      startedAt.set(id, Date.now())
      scheduleDelayedText(id)
    },

    onMeta(id, data) {
      const effort = data && data.effort
      if (!effort) return
      if (!metaAt.has(id)) metaAt.set(id, Date.now())
      const profile = profileFor(effort)
      page.patchMessage(id, (msg) => ({ ...msg, effort, waitingText: profile.initial }))
      scheduleDelayedText(id, effort)
    },

    onThinking(id, data) {
      const text = typeof data === 'string' ? data : data && data.text
      if (!text) return
      page.patchMessage(id, (msg) => ({ ...msg, thinkingText: msg.thinkingText + text }))
    },

    onBodyStart(id) {
      clearTimer(id)
      page.patchMessage(id, (msg) => ({
        ...finishThinking(id, msg),
        toolStatus: '',
        toolStatusError: false,
      }))
    },

    onToolStart(id, data) {
      clearTimer(id)
      const label = TOOL_LABELS[data && data.tool_name] || (data && data.tool_name) || '处理'
      page.patchMessage(id, (msg) => ({
        ...msg,
        toolStatus: `正在${label}…`,
        toolStatusError: false,
      }))
    },

    onToolEnd(id, data) {
      const result = data && data.result
      if (result === 'error') {
        const label = TOOL_LABELS[data.tool_name] || data.tool_name || '操作'
        page.patchMessage(id, (msg) => ({
          ...msg,
          toolStatus: `${label}失败，正在继续为您解答…`,
          toolStatusError: true,
        }))
        return
      }
      page.patchMessage(id, (msg) => ({ ...msg, toolStatus: '', toolStatusError: false }))
    },

    complete(id) {
      clearTimer(id)
      page.patchMessage(id, (msg) => finishThinking(id, msg))
      startedAt.delete(id)
      metaAt.delete(id)
    },

    fail(id) {
      clearTimer(id)
      startedAt.delete(id)
      metaAt.delete(id)
    },

    toggle(id) {
      page.patchMessage(id, (msg) =>
        msg.thinkingText ? { ...msg, thinkingExpanded: !msg.thinkingExpanded } : msg
      )
    },

    dispose() {
      timers.forEach((timer) => clearTimeout(timer))
      timers.clear()
      startedAt.clear()
      metaAt.clear()
    },
  }
}

module.exports = { createAssistantBubble, createAiBubbleState }
