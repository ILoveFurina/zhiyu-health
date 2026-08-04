const { apiBaseUrl } = require('./config')
const { getToken } = require('./auth')

function parseResponse(res) {
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
  const message = (data && data.message) || (typeof detail === 'string' && detail)
    || (detail && detail.message) || `舌苔辨证失败（${status}）`
  const error = new Error(message)
  error.detail = detail
  throw error
}

/**
 * 上传舌苔照片并触发中医辨证（票 17，照搬 15/16 上传链路，ADR-0024 合规边界）。
 *
 * 舌苔场景无分段 staging：my.uploadFile 单次只传一个 file，单张照片一次请求即上传+分析。
 * 返回体含通用免责 disclaimer 与中医专属免责 tcm_disclaimer（ADR-0024 第 2 条），卡片叠加两条。
 */
function uploadTonguePhoto({ requestId, conversationId, item }) {
  const mediaType = item.path.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg'
  return new Promise((resolve, reject) => {
    my.uploadFile({
      url: `${apiBaseUrl}/c/tongue-photos`,
      filePath: item.path,
      fileName: 'files',
      fileType: 'image',
      formData: {
        request_id: requestId,
        conversation_id: conversationId || '',
      },
      headers: { Authorization: `Bearer ${getToken()}` },
      timeout: 340000,
      success: (res) => {
        try {
          resolve(parseResponse(res))
        } catch (error) {
          reject(error)
        }
      },
      fail: () => reject(new Error('无法上传舌苔照片，请检查网络')),
    })
  })
}

module.exports = { uploadTonguePhoto }
