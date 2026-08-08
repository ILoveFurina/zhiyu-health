const { request } = require('../utils/request')

/**
 * 健康观测核验操作（票 61）：报告详情页对 AI 提取沉淀的观测逐项确认/纠错/排除。
 * 归属由 server-java 按登录态解析，请求体不带 profile/patient 字段。
 */
const confirm = (id) => request({ url: `/c/health-observations/${id}/confirm`, method: 'POST' })

const correct = (id, value) =>
  request({ url: `/c/health-observations/${id}/correct`, method: 'POST', data: { value } })

const reject = (id) => request({ url: `/c/health-observations/${id}/reject`, method: 'POST' })

module.exports = { confirm, correct, reject }
