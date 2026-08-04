const { ensureLogin } = require('../../utils/auth')
const { chooseTonguePhoto } = require('../../utils/tongue-picker')
const { uploadTonguePhoto } = require('../../utils/tongue-upload')

/**
 * 拍舌苔中医辨证 composer（票 17，照搬 15/16 composer，ADR-0024 合规边界）：
 * 仿 skin/diet-composer 的选择/上传/回落流程。图片作为 image kind 消息回落（原图存 MinIO），
 * 分析结果以 tongue_analysis 卡片作为 AI 消息回落，叠加通用免责 + 中医专属免责两条。
 */
module.exports = {
  openTonguePicker() {
    if (this.data.sending) return
    my.showActionSheet({
      items: ['拍摄舌苔', '从相册选择'],
      success: (result) => {
        chooseTonguePhoto(result.index)
          .then((items) => this.setData({ pendingTongue: { items } }))
          .catch((error) => {
            if (error.message !== '已取消' && !error.message.startsWith('未选择')) {
              my.showToast({ content: error.message, type: 'fail' })
            }
          })
      },
    })
  },

  removeTongueItem(e) {
    const items = this.data.pendingTongue.items.filter(
      (_, index) => index !== Number(e.currentTarget.dataset.index)
    )
    this.setData({ pendingTongue: items.length ? { ...this.data.pendingTongue, items } : null })
  },

  sendPendingTongue() {
    if (!this.data.pendingTongue || this.data.sending) return
    const items = this.data.pendingTongue.items
    const requestId = `tongue-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    this.setData({ sending: true, tongueProgress: '正在辨证…' })
    // 单张照片一次请求即上传+分析（my.uploadFile 单 file 限制）。
    const item = items[0]
    ensureLogin()
      .then(() => uploadTonguePhoto({
        requestId,
        conversationId: this.data.conversationId,
        item,
      }))
      .then((data) => this.finishTongue(items, data))
      .catch((error) => {
        this.setData({ sending: false, tongueProgress: '' })
        my.showToast({ content: error.message || '舌苔辨证失败', type: 'fail' })
      })
  },

  finishTongue(items, data) {
    // 图片消息（image kind）：server-java 已落 MinIO 并写 image 消息，前端同步展示缩略提示。
    const imageMessage = {
      id: ++this._msgSeq, role: 'user', kind: 'image',
      content: `舌苔照片（${items.length}张）`,
    }
    const resultMessage = {
      id: ++this._msgSeq, role: 'assistant', kind: 'tongue_analysis',
      card: data.result,
      disclaimer: data.disclaimer,
      // ADR-0024 第 2 条：中医专属免责叠加（server-java tcm_disclaimer 字段，双栈注入）
      tcmDisclaimer: data.tcm_disclaimer || '',
    }
    this.setData({
      messages: [...this.data.messages, imageMessage, resultMessage],
      conversationId: data.conversation_id,
      pendingTongue: null,
      tongueProgress: '',
      sending: false,
      anchorId: 'thread-bottom',
    })
  },
}
