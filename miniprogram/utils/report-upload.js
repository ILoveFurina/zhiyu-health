const { apiBaseUrl } = require('./config')
const { getToken } = require('./auth')
const { request } = require('./request')

function parseUploadResponse(res) {
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
  throw new Error((data && data.message) || (typeof detail === 'string' && detail)
    || (detail && detail.message)
    || `上传失败（${status}）`)
}

function uploadOne({ requestId, item, index, total }) {
  const mediaType = item.kind === 'pdf'
    ? 'application/pdf'
    : (item.path.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg')
  return new Promise((resolve, reject) => {
    my.uploadFile({
      url: `${apiBaseUrl}/c/report-interpretation-uploads`,
      filePath: item.path,
      fileName: 'file',
      fileType: item.kind === 'image' ? 'image' : undefined,
      formData: {
        request_id: requestId,
        page_index: String(index),
        total_files: String(total),
        media_type: mediaType,
      },
      headers: { Authorization: `Bearer ${getToken()}` },
      timeout: 340000,
      success: (res) => {
        try {
          resolve(parseUploadResponse(res))
        } catch (error) {
          reject(error)
        }
      },
      fail: () => reject(new Error('无法上传报告，请检查网络')),
    })
  })
}

async function uploadReport({ requestId, conversationId, items, onProgress }) {
  for (let index = 0; index < items.length; index += 1) {
    await uploadOne({ requestId, item: items[index], index, total: items.length })
    if (onProgress) onProgress(index + 1, items.length)
  }
  return request({
    url: '/c/report-interpretations/finalize',
    method: 'POST',
    timeout: 340000,
    data: { request_id: requestId, conversation_id: conversationId || null },
  })
}

module.exports = { uploadReport }
