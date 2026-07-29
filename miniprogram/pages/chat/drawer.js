const { listConversations, listMessages, deleteConversation } = require('../../services/conversations')
const { formatRelativeTime } = require('../../utils/time')

// 结构化卡片种类：历史回放时需把 JSON content 还原为 card 对象
const CARD_KINDS = ['doctor_recommendations', 'doctor_slots', 'appointment', 'appointments']
function isCardKind(kind) {
  return CARD_KINDS.indexOf(kind) !== -1
}

/**
 * 对话记录抽屉逻辑（票 27 决策 6/9/13）。
 * 以方法对象形式导出，在 chat 页 Page() 中展开合并；所有方法以 chat 页实例为 this。
 */
const drawerMethods = {
  /** 打开抽屉拉取列表；抽屉为同页浮层，打开不打断 SSE（决策 9）。 */
  openDrawer() {
    this.setData({ drawerOpen: true, drawerLoading: true })
    this.ensureLoginC()
      .then(() => listConversations())
      .then((conversations) => this.setConversations(conversations))
      .catch(() => my.showToast({ content: '对话记录加载失败', type: 'fail' }))
      .then(() => this.setData({ drawerLoading: false }))
  },

  closeDrawer() {
    this.setData({ drawerOpen: false })
  },

  /** 阻止抽屉面板点击冒泡到遮罩关闭（空函数）。 */
  noop() {},

  setConversations(conversations) {
    this.setData({
      conversations: conversations.map((c) => ({
        ...c,
        relative: formatRelativeTime(c.last_active_at),
        // 正在 sending 的当前会话前端禁用删除（决策 9）
        active: this.data.sending && c.id === this.data.conversationId,
      })),
    })
  },

  /** 「新对话」= 纯前端态重置：不发请求、不建会话（决策 6）。 */
  startNewConversation() {
    this.resetChatState()
    this.setData({ drawerOpen: false })
  },

  /** 点选历史会话：全量加载消息回放，并在该会话内续聊（决策 4/13）。 */
  selectConversation(e) {
    const conversationId = e.currentTarget.dataset.id
    if (conversationId === this.data.conversationId) {
      this.setData({ drawerOpen: false })
      return
    }
    my.showLoading({ content: '加载会话…' })
    listMessages(conversationId)
      .then((messages) => {
        this.stopTypewriter()
        this.setData({
          messages: messages.map((m) => this.replayMessage(m)),
          conversationId,
          redFlag: null,
          drawerOpen: false,
          anchorId: 'thread-bottom',
        })
      })
      .catch(() => my.showToast({ content: '会话加载失败', type: 'fail' }))
      .then(() => my.hideLoading())
  },

  /** 历史消息回放：原样渲染用户/红线/卡片，AI 文本定格为完整内容（不打字机）。 */
  replayMessage(m) {
    if (isCardKind(m.kind)) {
      let card = m.content
      try {
        card = JSON.parse(m.content)
      } catch (e) {
        card = { raw: m.content }
      }
      return { id: ++this._msgSeq, role: m.role, kind: m.kind, card, disclaimer: m.disclaimer }
    }
    return {
      id: ++this._msgSeq,
      role: m.role,
      kind: m.kind,
      content: m.content,
      disclaimer: m.disclaimer,
      streaming: false,
    }
  },

  /** 删除手势：长按 -> actionSheet「删除」-> 二次 confirm -> DELETE（决策 5）。 */
  onConversationLongPress(e) {
    const conversationId = e.currentTarget.dataset.id
    // 正在 sending 的当前会话禁用删除（决策 9）
    if (this.data.sending && conversationId === this.data.conversationId) {
      my.showToast({ content: '对话进行中，暂无法删除', type: 'fail' })
      return
    }
    my.showActionSheet({
      itemList: ['删除'],
      success: (res) => {
        if (res.tapIndex !== 0) return
        my.confirm({
          title: '删除对话',
          content: '删除后不可恢复，确认删除这段对话吗？',
          confirmButtonText: '删除',
          cancelButtonText: '取消',
          success: (result) => {
            if (!result.confirm) return
            this.removeConversation(conversationId)
          },
        })
      },
    })
  },

  removeConversation(conversationId) {
    deleteConversation(conversationId)
      .then(() => {
        // 本地从列表移除该列表项，不重新拉取（决策 13）
        this.setData({
          conversations: this.data.conversations.filter((c) => c.id !== conversationId),
        })
        // 若删的是 chat 页当前会话，则重置为空态（决策 13）
        if (conversationId === this.data.conversationId) {
          this.startNewConversation()
        }
        my.showToast({ content: '已删除', type: 'success' })
      })
      .catch(() => my.showToast({ content: '删除失败，请稍后重试', type: 'fail' }))
  },
}

module.exports = { drawerMethods }
