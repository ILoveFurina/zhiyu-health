// 票 45：语音双向（ASR 输入 + TTS 播报）端侧封装。
//
// 与 contracts/voice.json 的 asr_enabled/tts_enabled 对齐（端侧无法读契约 JSON，
// 此文件是 miniprogram 侧的本地镜像；契约变更须同步更新）。骨架阶段两端均 false，
// 语音入口降级为文字输入不阻塞演示；开通后改此文件双开关为 true 即点亮入口。
//
// ASR：my.getRecorderManager 录音 -> POST /c/asr multipart -> 文字回填输入框（可见可改不自动发）。
// TTS：按需点击 -> GET /c/tts（text 经 query）-> my.downloadFile 拉取音频临时文件 -> InnerAudioContext 播放/停止。
// 未配置/超时/失败三情况降级文字，不阻塞演示。
//
// 选型说明：server-java /c/tts 同时支持 POST（契约对称、curl 友好）与 GET（query 传 text）。
// 小程序端用 GET + my.downloadFile 是因为 InnerAudioContext 需要文件路径，而 my.request
// 的 arraybuffer 响应无法直接喂给音频上下文；downloadFile 返回的 apFilePath 可直接播放。

const { apiBaseUrl } = require('./config')
const { getToken } = require('./auth')

// 本地镜像 contracts/voice.json 的 enabled 开关（契约变更须同步更新）：
// asr_enabled=true（票 58 点亮，Fake 阶段识别文字回填输入框）；tts_enabled 保持 false
const ASR_ENABLED = true
const TTS_ENABLED = false

function isAsrEnabled() {
  return ASR_ENABLED
}

function isTtsEnabled() {
  return TTS_ENABLED
}

function parseAsrResponse(res) {
  const status = res.statusCode || res.status
  let data = res.data
  if (typeof data === 'string') {
    try {
      data = JSON.parse(data)
    } catch (_) {
      data = {}
    }
  }
  if (status >= 200 && status < 300) return data
  const detail = data && data.detail
  const message =
    (typeof detail === 'string' && detail) || (detail && detail.message) || `识别失败（${status}）`
  const error = new Error(message)
  error.status = status
  error.code = detail && detail.code
  throw error
}

/** 录音 -> POST /c/asr multipart -> 识别文字。filePath 为录音临时文件路径。 */
function recognizeSpeech({ filePath }) {
  return new Promise((resolve, reject) => {
    my.uploadFile({
      url: `${apiBaseUrl}/c/asr`,
      filePath,
      fileName: 'audio',
      fileType: 'audio',
      headers: { Authorization: `Bearer ${getToken()}` },
      timeout: 30000,
      success: (res) => {
        try {
          resolve(parseAsrResponse(res))
        } catch (error) {
          reject(error)
        }
      },
      fail: () => reject(new Error('语音识别服务暂不可用')),
    })
  })
}

/** GET /c/tts -> 音频临时文件路径（my.downloadFile 返回 apFilePath）。 */
function synthesizeSpeech({ text }) {
  return new Promise((resolve, reject) => {
    my.downloadFile({
      url: `${apiBaseUrl}/c/tts?text=${encodeURIComponent(text)}`,
      method: 'GET',
      headers: { Authorization: `Bearer ${getToken()}` },
      timeout: 30000,
      success: (res) => {
        const status = res.statusCode || res.status
        if (status >= 200 && status < 300) {
          resolve(res.apFilePath)
        } else {
          reject(new Error(`合成失败（${status}）`))
        }
      },
      fail: () => reject(new Error('语音合成服务暂不可用')),
    })
  })
}

module.exports = {
  isAsrEnabled,
  isTtsEnabled,
  recognizeSpeech,
  synthesizeSpeech,
}
