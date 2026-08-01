const { request } = require('../utils/request')

/** 报告解读历史记录（票 41 API，报告解读入口页消费）。 */
const list = () => request({ url: '/c/report-interpretations' })

module.exports = { list }
