const { request } = require('../utils/request')

const listPrescriptions = () => request({ url: '/c/prescriptions' })
const listMessages = () => request({ url: '/c/messages' })
// 服药打卡提醒：站内消息通道聚合 PENDING 提醒，点击"已服用"推进 CHECKED（ADR-0017）。
const listMedCheckins = () => request({ url: '/c/med-checkins' })
const checkMedCheckin = (id) => request({ url: `/c/med-checkins/${id}/check`, method: 'POST' })

module.exports = { listPrescriptions, listMessages, listMedCheckins, checkMedCheckin }
