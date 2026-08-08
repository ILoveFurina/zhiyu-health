const { request } = require('../utils/request')

/** 报告解读历史记录（票 41 API，报告解读入口页消费）。 */
const list = () => request({ url: '/c/report-interpretations' })

/** 报告解读详情（票 61，独立详情页消费，含逐项沉淀/核验状态）。 */
const getDetail = (id) => request({ url: `/c/report-interpretations/${id}` })

module.exports = { list, getDetail }
