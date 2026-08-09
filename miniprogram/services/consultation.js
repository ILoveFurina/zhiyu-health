const { request } = require('../utils/request')
const { apiBaseUrl } = require('../utils/config')
const { getToken } = require('../utils/auth')

/** my.uploadFile 响应解析：与 utils/skin-upload.js 的 parseResponse 同构，错误带 detail 文案。 */
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
  const message =
    (data && data.message) ||
    (typeof detail === 'string' && detail) ||
    (detail && detail.message) ||
    `图片发送失败（${status}）`
  const error = new Error(message)
  error.detail = detail
  throw error
}

/** 开始或恢复当前激活健康档案的预问诊草稿；无档案时后端 409。 */
function startOrResumeDraft() {
  return request({ url: '/c/preconsultation-drafts', method: 'POST', data: {} })
}

function getDraft(draftId) {
  return request({ url: `/c/preconsultation-drafts/${draftId}` })
}

function listConsultationProgress() {
  return request({ url: '/c/preconsultation-drafts/progress' })
}

function abandonDraft(draftId) {
  return request({ url: `/c/preconsultation-drafts/${draftId}/abandon`, method: 'POST' })
}

/** 患者确认摘要后创建在线问诊单（幂等，已提交草稿返回关联问诊单）。 */
function createConsultation(draftId) {
  return request({ url: '/c/online-consultations', method: 'POST', data: { draft_id: draftId } })
}

function listConsultations() {
  return request({ url: '/c/online-consultations' })
}

function getConsultation(id) {
  return request({ url: `/c/online-consultations/${id}` })
}

function cancelConsultation(id) {
  return request({ url: `/c/online-consultations/${id}/cancel`, method: 'POST' })
}

/** 复用原病情摘要重新提交；返回的是新问诊单，此后一律使用新 id。 */
function resubmitConsultation(id) {
  return request({ url: `/c/online-consultations/${id}/resubmit`, method: 'POST' })
}

/** 增量拉取医患消息（after_id 之后，按 id 升序）。 */
function listMessages(id, afterId) {
  return request({ url: `/c/online-consultations/${id}/messages?after_id=${afterId || 0}` })
}

/** 发送医患消息；非 IN_PROGRESS 状态后端 409。 */
function sendMessage(id, content) {
  return request({ url: `/c/online-consultations/${id}/messages`, method: 'POST', data: { content } })
}

/**
 * 患者发送问诊图片（票 58，ADR-0029）：multipart 上传单张图片，成功返回 {message}（kind=image）。
 * 图片是消息本体，失败（含 MinIO 不可用）即发送失败，前端提示重试，不降级。
 */
function uploadPhoto(id, filePath) {
  return new Promise((resolve, reject) => {
    my.uploadFile({
      url: `${apiBaseUrl}/c/online-consultations/${id}/photos`,
      filePath,
      fileName: 'file',
      fileType: 'image',
      headers: { Authorization: `Bearer ${getToken()}` },
      timeout: 60000,
      success: (res) => {
        try {
          resolve(parseUploadResponse(res))
        } catch (error) {
          reject(error)
        }
      },
      fail: () => reject(new Error('图片发送失败，请检查网络')),
    })
  })
}

module.exports = {
  startOrResumeDraft,
  getDraft,
  listConsultationProgress,
  abandonDraft,
  createConsultation,
  listConsultations,
  getConsultation,
  cancelConsultation,
  resubmitConsultation,
  listMessages,
  sendMessage,
  uploadPhoto,
}
