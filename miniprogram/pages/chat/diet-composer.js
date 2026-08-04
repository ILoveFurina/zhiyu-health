const { ensureLogin } = require('../../utils/auth')
const { chooseDietPhoto } = require('../../utils/diet-picker')
const { uploadDietPhoto } = require('../../utils/diet-upload')

/**
 * 拍饮食分析 composer（票 16，照搬 15 皮肤 composer）：仿 skin-composer 的选择/上传/回落流程。
 * 图片作为 image kind 消息回落（原图存 MinIO，前端按 object_key 回拉），
 * 分析结果以 diet_analysis 卡片作为 AI 消息回落，两者分离（ADR-0023）。
 */
module.exports = {
  openDietPicker() {
    if (this.data.sending) return
    my.showActionSheet({
      items: ['拍摄饮食', '从相册选择'],
      success: (result) => {
        chooseDietPhoto(result.index)
          .then((items) => this.setData({ pendingDiet: { items } }))
          .catch((error) => {
            if (error.message !== '已取消' && !error.message.startsWith('未选择')) {
              my.showToast({ content: error.message, type: 'fail' })
            }
          })
      },
    })
  },

  removeDietItem(e) {
    const items = this.data.pendingDiet.items.filter(
      (_, index) => index !== Number(e.currentTarget.dataset.index)
    )
    this.setData({ pendingDiet: items.length ? { ...this.data.pendingDiet, items } : null })
  },

  sendPendingDiet() {
    if (!this.data.pendingDiet || this.data.sending) return
    const items = this.data.pendingDiet.items
    const requestId = `diet-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    this.setData({ sending: true, dietProgress: '正在分析…' })
    // 单张照片一次请求即上传+分析（my.uploadFile 单 file 限制）。
    const item = items[0]
    ensureLogin()
      .then(() => uploadDietPhoto({
        requestId,
        conversationId: this.data.conversationId,
        item,
      }))
      .then((data) => this.finishDiet(items, data))
      .catch((error) => {
        this.setData({ sending: false, dietProgress: '' })
        my.showToast({ content: error.message || '饮食分析失败', type: 'fail' })
      })
  },

  finishDiet(items, data) {
    // 图片消息（image kind）：后端已落 MinIO 并写 image 消息，前端同步展示缩略提示。
    const imageMessage = {
      id: ++this._msgSeq, role: 'user', kind: 'image',
      content: `饮食照片（${items.length}张）`,
    }
    const resultMessage = {
      id: ++this._msgSeq, role: 'assistant', kind: 'diet_analysis',
      card: data.result, disclaimer: data.disclaimer,
    }
    this.setData({
      messages: [...this.data.messages, imageMessage, resultMessage],
      conversationId: data.conversation_id,
      pendingDiet: null,
      dietProgress: '',
      sending: false,
      anchorId: 'thread-bottom',
    })
  },
}
