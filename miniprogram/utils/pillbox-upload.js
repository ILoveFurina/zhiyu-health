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
    || (detail && detail.message) || `药盒识别失败（${status}）`
  const error = new Error(message)
  error.detail = detail
  throw error
}

/**
 * 上传药盒照片并触发视觉识别 + 药品查询（票 14，ADR-0025）。
 *
 * 与 15/16/17 上传链路同构：my.uploadFile 单次只传一个 file，单张照片一次请求即上传+分析。
 * 视觉只提候选药名，药品匹配与禁忌判定全在 server-java 完成，返回双出口
 * medication_info + medication_safety 卡片（或 not_found=true 引导文案）。
 */
function uploadPillBoxPhoto({ requestId, conversationId, item }) {
  const mediaType = item.path.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg'
  return new Promise((resolve, reject) => {
    my.uploadFile({
      url: `${apiBaseUrl}/c/pill-box-photos`,
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
      fail: () => reject(new Error('无法上传药盒照片，请检查网络')),
    })
  })
}

module.exports = { uploadPillBoxPhoto }
