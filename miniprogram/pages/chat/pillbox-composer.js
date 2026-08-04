const { ensureLogin } = require('../../utils/auth')
const { choosePillBoxPhoto } = require('../../utils/pillbox-picker')
const { uploadPillBoxPhoto } = require('../../utils/pillbox-upload')

/**
 * 拍药盒 composer（票 14，ADR-0025）：仿 15/16/17 composer 的选择/上传/回落流程。
 * 差异化：vision 只提候选药名，server-java 查 medications + 规则引擎产出双出口。
 * 图片作为 image kind 消息回落（原图存 MinIO），分析结果以 medication_info +
 * medication_safety 两条独立 AI 消息回落（比 15/16/17 多一条）。
 */
module.exports = {
  openPillboxPicker() {
    if (this.data.sending) return
    my.showActionSheet({
      items: ['拍摄药盒', '从相册选择'],
      success: (result) => {
        choosePillBoxPhoto(result.index)
          .then((items) => this.setData({ pendingPillbox: { items } }))
          .catch((error) => {
            if (error.message !== '已取消' && !error.message.startsWith('未选择')) {
              my.showToast({ content: error.message, type: 'fail' })
            }
          })
      },
    })
  },

  removePillboxItem(e) {
    const items = this.data.pendingPillbox.items.filter(
      (_, index) => index !== Number(e.currentTarget.dataset.index)
    )
    this.setData({ pendingPillbox: items.length ? { ...this.data.pendingPillbox, items } : null })
  },

  sendPendingPillbox() {
    if (!this.data.pendingPillbox || this.data.sending) return
    const items = this.data.pendingPillbox.items
    const requestId = `pillbox-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    this.setData({ sending: true, pillboxProgress: '正在识别药盒…' })
    // 单张照片一次请求即上传+分析（my.uploadFile 单 file 限制）。
    const item = items[0]
    ensureLogin()
      .then(() => uploadPillBoxPhoto({
        requestId,
        conversationId: this.data.conversationId,
        item,
      }))
      .then((data) => this.finishPillbox(items, data))
      .catch((error) => {
        this.setData({ sending: false, pillboxProgress: '' })
        my.showToast({ content: error.message || '药盒识别失败', type: 'fail' })
      })
  },

  finishPillbox(items, data) {
    // 图片消息（image kind）：server-java 已落 MinIO 并写 image 消息，前端同步展示缩略提示。
    const imageMessage = {
      id: ++this._msgSeq, role: 'user', kind: 'image',
      content: `药盒照片（${items.length}张）`,
    }
    const newMessages = [imageMessage]
    // ADR-0025 差异化点 3：双出口两条独立 AI 消息。
    // not_found（未识别/未匹配）时后端已落 text 消息，前端只补一条提示。
    if (data.not_found) {
      newMessages.push({
        id: ++this._msgSeq, role: 'assistant', kind: 'text',
        content: '未能识别药盒上的药名或未匹配到药品，请重拍清晰的药盒照片或使用「查药品」入口输入药名。',
        disclaimer: '仅供参考，不替代医生诊断',
      })
    } else {
      // 说明书卡片（medication_info）
      newMessages.push({
        id: ++this._msgSeq, role: 'assistant', kind: 'medication_info',
        card: data.medication_info,
        disclaimer: '仅供参考，不替代医生诊断',
      })
      // 安全结果卡片（medication_safety）
      newMessages.push({
        id: ++this._msgSeq, role: 'assistant', kind: 'medication_safety',
        card: data.medication_safety,
        disclaimer: '仅供参考，不替代医生诊断',
      })
    }
    this.setData({
      messages: [...this.data.messages, ...newMessages],
      conversationId: data.conversation_id,
      pendingPillbox: null,
      pillboxProgress: '',
      sending: false,
      anchorId: 'thread-bottom',
    })
  },
}
