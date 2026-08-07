const { request } = require('../utils/request')

/** 开始或恢复当前激活健康档案的预问诊草稿；无档案时后端 409。 */
function startOrResumeDraft() {
  return request({ url: '/c/preconsultation-drafts', method: 'POST', data: {} })
}

function getDraft(draftId) {
  return request({ url: `/c/preconsultation-drafts/${draftId}` })
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

module.exports = {
  startOrResumeDraft,
  getDraft,
  createConsultation,
  listConsultations,
  getConsultation,
  cancelConsultation,
  resubmitConsultation,
  listMessages,
  sendMessage,
}
