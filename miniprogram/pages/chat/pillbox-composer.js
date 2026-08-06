const { ensureLogin } = require('../../utils/auth')
const { createChatChannel } = require('../../utils/chat-stream')
const { choosePillBoxPhoto } = require('../../utils/pillbox-picker')
const { uploadPillBoxPhoto } = require('../../utils/pillbox-upload')

/**
 * 拍药盒 composer（票 51，ADR-0028）：选择/上传 -> vision OCR 提名 ->
 * 按 drug_names[0] 自动发送 medication_name 信封，说明书经实时通道流式渲染
 * （复用主对话 text 气泡）。票 14 的双出口卡片（medication_info/medication_safety）
 * 已删除：说明书来自 LLM 通用语料，C 端不做个性化禁忌判定。
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
    // 进度文案分级（票 51）：识别药名 -> 生成说明书
    this.setData({ sending: true, pillboxProgress: '正在识别药名…' })
    // 单张照片一次请求即上传+识别（my.uploadFile 单 file 限制）。
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
    if (!data.recognized) {
      // 未识别药名：后端已落 text 引导消息，响应携带 hint，前端追加文本气泡展示，
      // 否则用户只见照片气泡没有任何解释。
      if (data.hint) {
        newMessages.push({
          id: ++this._msgSeq, role: 'assistant', kind: 'text',
          content: data.hint,
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
      return
    }
    const drugNames = data.drug_names || []
    if (drugNames.length > 1) {
      // 多候选：主候选自动查说明书，其余提示可手动输入药名查看
      newMessages.push({
        id: ++this._msgSeq, role: 'assistant', kind: 'text',
        content: `还识别到：${drugNames.slice(1).join('、')}，可直接输入药名查看`,
      })
    }
    this.setData({
      messages: [...this.data.messages, ...newMessages],
      conversationId: data.conversation_id,
      pendingPillbox: null,
      pillboxProgress: '正在生成药品说明…',
    })
    // 按主候选自动发送 medication_name 信封（文字版能力同一信封保留）
    this.startMedicationRound(drugNames[0])
  },

  /** 通用药品说明书流（票 51）：medication_name 信封，流式渲染复用主对话 text 气泡。 */
  startMedicationRound(drugName) {
    const userMsg = { id: ++this._msgSeq, role: 'user', kind: 'text', content: drugName }
    const aiMsg = {
      id: ++this._msgSeq,
      role: 'assistant',
      kind: 'text',
      content: '',
      disclaimer: '',
      emotion: 'calm',
      soothingText: '',
      streaming: true,
    }
    this.setData({
      messages: [...this.data.messages, userMsg, aiMsg],
      anchorId: 'thread-bottom',
    })

    if (!this._chatChannel) this._chatChannel = createChatChannel()
    this._chatChannel.send({
      requestId: `med-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`,
      medicationName: drugName,
      conversationId: this.data.conversationId,
      handlers: {
        onMeta: (data) => this.setData({ conversationId: data.conversation_id }),
        onFallback: () => this.patchMessage(aiMsg.id, (msg) => ({ ...msg, content: '' })),
        onToken: (data) => this.streamAssistantToken(aiMsg.id, data.text),
        onAssistant: (data) => this.finishAssistant(aiMsg.id, data),
        onDone: () => {
          this.setData({ pillboxProgress: '' })
          this.completeRound()
        },
        onError: (err) => {
          this.setData({ pillboxProgress: '' })
          this.failRound(aiMsg.id, err)
        },
      },
    })
  },
}
