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
    || (detail && detail.message) || `饮食分析失败（${status}）`
  const error = new Error(message)
  error.detail = detail
  throw error
}

/**
 * 上传饮食照片并触发分析（票 16，照搬 15 皮肤上传链路）。
 *
 * 饮食场景无分段 staging（与报告解读不同）：my.uploadFile 单次只传一个 file，
 * 故单张照片一次请求即上传+分析；多张时逐张上传到同一 request_id 下后端按 files 列表接收。
 * 实际使用以单张拍摄为主（chooseImage camera count=1）。
 */
function uploadDietPhoto({ requestId, conversationId, item }) {
  const mediaType = item.path.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg'
  return new Promise((resolve, reject) => {
    my.uploadFile({
      url: `${apiBaseUrl}/c/diet-photos`,
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
      fail: () => reject(new Error('无法上传饮食照片，请检查网络')),
    })
  })
}

module.exports = { uploadDietPhoto }
