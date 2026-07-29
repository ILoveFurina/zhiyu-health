const { request } = require('../utils/request')

/** 当前患者的对话记录列表（最近活跃倒序，上限 50）。 */
function listConversations() {
  return request({ url: '/c/conversations' })
}

/** 进入历史会话时全量返回该会话消息，用于 UI 回放。 */
function listMessages(conversationId) {
  return request({ url: `/c/conversations/${conversationId}/messages` })
}

/** 硬删会话与消息，不影响挂号单等业务实体。 */
function deleteConversation(conversationId) {
  return request({ url: `/c/conversations/${conversationId}`, method: 'DELETE' })
}

module.exports = { listConversations, listMessages, deleteConversation }
