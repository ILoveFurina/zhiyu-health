const { ensureLogin } = require('../../utils/auth')
const { chooseSkinPhoto } = require('../../utils/skin-picker')
const { uploadSkinPhoto } = require('../../utils/skin-upload')

/**
 * 拍皮肤分析 composer（票 15）：仿 report-composer 的选择/上传/回落流程。
 * 图片作为 image kind 消息回落（原图存 MinIO，前端按 object_key 回拉），
 * 分析结果以 skin_analysis 卡片作为 AI 消息回落，两者分离（ADR-0023）。
 */
module.exports = {
  openSkinPicker() {
    if (this.data.sending) return
    my.showActionSheet({
      items: ['拍摄皮肤', '从相册选择'],
      success: (result) => {
        chooseSkinPhoto(result.index)
          .then((items) => this.setData({ pendingSkin: { items } }))
          .catch((error) => {
            if (error.message !== '已取消' && !error.message.startsWith('未选择')) {
              my.showToast({ content: error.message, type: 'fail' })
            }
          })
      },
    })
  },

  removeSkinItem(e) {
    const items = this.data.pendingSkin.items.filter(
      (_, index) => index !== Number(e.currentTarget.dataset.index)
    )
    this.setData({ pendingSkin: items.length ? { ...this.data.pendingSkin, items } : null })
  },

  sendPendingSkin() {
    if (!this.data.pendingSkin || this.data.sending) return
    const items = this.data.pendingSkin.items
    const requestId = `skin-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    this.setData({ sending: true, skinProgress: '正在分析…' })
    // 单张照片一次请求即上传+分析（my.uploadFile 单 file 限制）。
    const item = items[0]
    ensureLogin()
      .then(() => uploadSkinPhoto({
        requestId,
        conversationId: this.data.conversationId,
        item,
      }))
      .then((data) => this.finishSkin(items, data))
      .catch((error) => {
        this.setData({ sending: false, skinProgress: '' })
        my.showToast({ content: error.message || '皮肤分析失败', type: 'fail' })
      })
  },

  finishSkin(items, data) {
    // 图片消息（image kind）：后端已落 MinIO 并写 image 消息，前端同步展示缩略提示。
    const imageMessage = {
      id: ++this._msgSeq, role: 'user', kind: 'image',
      content: `皮肤照片（${items.length}张）`,
    }
    const resultMessage = {
      id: ++this._msgSeq, role: 'assistant', kind: 'skin_analysis',
      card: data.result, disclaimer: data.disclaimer,
    }
    this.setData({
      messages: [...this.data.messages, imageMessage, resultMessage],
      conversationId: data.conversation_id,
      pendingSkin: null,
      skinProgress: '',
      sending: false,
      anchorId: 'thread-bottom',
    })
  },
}
