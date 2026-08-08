const { listConversations, listMessages, deleteConversation } = require('../../services/conversations')
const { formatRelativeTime } = require('../../utils/time')
const { ensureLogin } = require('../../utils/auth')
const { isCardKind } = require('../../utils/message-kinds')
const { defaultSelectedDate } = require('../../utils/department-slots')
const { soothingTextFor } = require('../../utils/emotion')
const { parseMarkdown } = require('../../utils/markdown')
const { apiBaseUrl } = require('../../utils/config')

/**
 * 对话记录抽屉逻辑（票 27 决策 6/9/13）。
 * 以方法对象形式导出，在 chat 页 Page() 中展开合并；所有方法以 chat 页实例为 this。
 */
const drawerMethods = {
  /** 打开抽屉拉取列表；抽屉为同页浮层，打开不打断 SSE（决策 9）。 */
  openDrawer() {
    this.setData({ drawerOpen: true, drawerLoading: true })
    ensureLogin()
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

  /** 点选历史会话：委托 openConversationById（决策 4/13）。 */
  selectConversation(e) {
    this.openConversationById(e.currentTarget.dataset.id)
  },

  /**
   * 按 id 打开历史会话：全量加载消息回放，并在该会话内续聊（决策 4/13）。
   * 供抽屉点选与 tab 外入口（报告解读记录，票 42 阶段三）复用。
   */
  openConversationById(conversationId) {
    if (conversationId === this.data.conversationId) {
      this.setData({ drawerOpen: false })
      return
    }
    my.showLoading({ content: '加载会话…' })
    listMessages(conversationId)
      .then((messages) => {
        this.setData({
          messages: messages.map((m) => this.replayMessage(m)).filter(Boolean),
          conversationId,
          redFlag: null,
          drawerOpen: false,
          anchorId: 'thread-bottom',
        })
      })
      .catch(() => my.showToast({ content: '会话加载失败', type: 'fail' }))
      .then(() => my.hideLoading())
  },

  /** 消费报告解读记录指定的待打开会话（票 42 阶段三，经 globalData 传递）。 */
  consumeOpenConversation() {
    const app = getApp()
    const conversationId = app.globalData.pendingOpenConversationId
    if (!conversationId) return
    app.globalData.pendingOpenConversationId = null
    this.openConversationById(conversationId)
  },

  /** 历史消息回放：原样渲染用户/红线/卡片，AI 文本定格为完整内容（不打字机）。 */
  replayMessage(m) {
    // report_upload（票 63）：仅作报告记录关联的元数据消息，回显已由紧随其后的
    // image 消息（原图留 MinIO）承担，回放跳过避免与图片气泡重复。
    if (m.kind === 'report_upload') {
      return null
    }
    // image kind（票 15 ADR-0023）：content 是 {object_key, media_type} JSON，
    // 按 object_key 回拉 MinIO 原图（经 server-java 图片代理端点，key 即凭证）。
    if (m.kind === 'image') {
      let url = ''
      try {
        const image = JSON.parse(m.content)
        if (image.object_key) {
          url = `${apiBaseUrl}/c/photos?key=${encodeURIComponent(image.object_key)}`
        }
      } catch (e) {
        // 解析失败时 url 留空，模板走无图兜底文字
      }
      return {
        id: ++this._msgSeq,
        role: m.role,
        kind: 'image',
        content: '照片',
        url,
        disclaimer: '',
        streaming: false,
      }
    }
    if (isCardKind(m.kind)) {
      let card = m.content
      try {
        card = JSON.parse(m.content)
      } catch (e) {
        card = { raw: m.content }
      }
      if (m.kind === 'report_interpretation' && card.result) {
        return {
          id: ++this._msgSeq,
          role: m.role,
          kind: m.kind,
          card: card.result,
          disclaimer: card.disclaimer || m.disclaimer,
        }
      }
      // skin_analysis 与 report_interpretation 同构：content 是 {result, disclaimer} 包裹
      if (m.kind === 'skin_analysis' && card.result) {
        return {
          id: ++this._msgSeq,
          role: m.role,
          kind: m.kind,
          card: card.result,
          disclaimer: card.disclaimer || m.disclaimer,
        }
      }
      // diet_analysis 与 skin_analysis 同构：content 是 {result, disclaimer} 包裹（票 16）
      if (m.kind === 'diet_analysis' && card.result) {
        return {
          id: ++this._msgSeq,
          role: m.role,
          kind: m.kind,
          card: card.result,
          disclaimer: card.disclaimer || m.disclaimer,
        }
      }
      // tongue_analysis 与 diet 同构，但叠加中医专属免责（ADR-0024，票 17）
      if (m.kind === 'tongue_analysis' && card.result) {
        return {
          id: ++this._msgSeq,
          role: m.role,
          kind: m.kind,
          card: card.result,
          disclaimer: card.disclaimer || m.disclaimer,
          tcmDisclaimer: card.tcm_disclaimer || '',
        }
      }
      // 票 50：科室号源卡回放与实时下发一致——成功卡补默认选中日期（受控组件必需）；
      // disclaimer 优先取卡片 JSON 内字段，避免历史消息 disclaimer 列缺失时漏出免责
      if (m.kind === 'department_slots') {
        const replayCard =
          card.status === 'ok' ? { ...card, selectedDate: defaultSelectedDate(card) } : card
        return {
          id: ++this._msgSeq,
          role: m.role,
          kind: m.kind,
          card: replayCard,
          disclaimer: card.disclaimer || m.disclaimer,
        }
      }
      // 票 65：科室选择卡回放——无需加工，disclaimer 同样优先取卡片 JSON 内字段
      if (m.kind === 'department_options') {
        return {
          id: ++this._msgSeq,
          role: m.role,
          kind: m.kind,
          card,
          disclaimer: card.disclaimer || m.disclaimer,
        }
      }
      return { id: ++this._msgSeq, role: m.role, kind: m.kind, card, disclaimer: m.disclaimer }
    }
    return {
      id: ++this._msgSeq,
      role: m.role,
      kind: m.kind,
      content: m.content,
      // 票 52：历史回放的 AI 文本同样按 Markdown 块渲染
      blocks: m.role === 'assistant' ? parseMarkdown(m.content) : undefined,
      disclaimer: m.disclaimer,
      // 票 44：历史回看复现情绪色；安抚语按本地映射补回（后端只存 emotion 列不存 soothing_text）
      emotion: m.emotion || 'calm',
      soothingText: soothingTextFor(m.emotion),
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
