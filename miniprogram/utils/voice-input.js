// 语音输入页面 mixin（按住说话 → ASR → 文字回填输入框，可编辑后发送）。
// 抽取自 pages/chat 与 consult/doctor 的同构实现（票 58 链路）；当前仅 consult/preconsult
// 使用，chat/doctor 两份已在真机验证的拷贝保持不动，将来收敛时逐页切换、逐页真机验收。
//
// 宿主约定（三页字段同名，是本次抽取的前提）：
//   data: asrEnabled / recording / voiceHint / voiceHintError / inputValue / canSend / sending
//   页面实例字段 _recorder / _voiceWatchdog / _voiceCancelled 由本 mixin 惰性挂载；
//   onUnload 必须调 clearVoiceTimers()。
// 真机坑（票 45/58，勿回退）：模拟器 onStop 不触发 → 15s 看门狗兜底；
// getRecorderManager 单例只注册一次监听；划出取消不识别；识别失败降级提示直接打字。

const { recognizeSpeech } = require('./voice')

const voiceInput = {
  ensureRecorder() {
    if (this._recorder) return
    this._recorder = my.getRecorderManager()
    this._recorder.onStop((res) => {
      clearTimeout(this._voiceWatchdog)
      this.setData({ recording: false, voiceHint: '识别中…', voiceHintError: false })
      if (this._voiceCancelled) {
        this._voiceCancelled = false
        this.setData({ voiceHint: '' })
        return
      }
      recognizeSpeech({ filePath: res.tempFilePath })
        .then((result) => {
          this.setData({
            inputValue: result.text || '',
            canSend: (result.text || '').trim().length > 0,
            voiceHint: '',
            voiceHintError: false,
          })
        })
        .catch(() => {
          this.setData({ voiceHint: '语音识别失败，请直接打字', voiceHintError: true })
        })
    })
    this._recorder.onError(() => {
      clearTimeout(this._voiceWatchdog)
      this.setData({ recording: false, voiceHint: '录音失败，请直接打字', voiceHintError: true })
    })
  },

  onVoiceTouchStart() {
    if (!this.data.asrEnabled || this.data.sending || this.data.recording) return
    this.ensureRecorder()
    this._voiceCancelled = false
    this._recorder.start({ duration: 60000, sampleRate: 16000, numberOfChannels: 1, format: 'wav' })
    this.setData({ recording: true, voiceHint: '松开发送识别', voiceHintError: false })
  },

  onVoiceTouchEnd() {
    if (!this.data.recording) return
    this.setData({ recording: false, voiceHint: '识别中…', voiceHintError: false })
    // IDE 模拟器不支持录音 API（支付宝官方文档：以真机为准），onStop 可能永不触发；
    // 看门狗兜底避免“识别中”卡死，真机上 onStop 到达即清除本定时器
    this._voiceWatchdog = setTimeout(() => {
      this.setData({ voiceHint: '当前环境不支持录音，请用真机调试或直接打字', voiceHintError: true })
    }, 15000)
    if (this._recorder) this._recorder.stop()
  },

  onVoiceTouchCancel() {
    if (!this.data.recording) return
    this._voiceCancelled = true
    clearTimeout(this._voiceWatchdog)
    this.setData({ recording: false, voiceHint: '' })
    if (this._recorder) this._recorder.stop()
  },

  clearVoiceTimers() {
    if (this._voiceWatchdog) {
      clearTimeout(this._voiceWatchdog)
      this._voiceWatchdog = null
    }
  },
}

module.exports = { voiceInput }
