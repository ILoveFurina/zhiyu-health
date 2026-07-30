const { request } = require('../utils/request')

const listPrescriptions = () => request({ url: '/c/prescriptions' })
const listMessages = () => request({ url: '/c/messages' })

module.exports = { listPrescriptions, listMessages }
