const { request } = require('../utils/request')

const listPrescriptions = () => request({ url: '/api/c/prescriptions' })
const listMessages = () => request({ url: '/api/c/messages' })

module.exports = { listPrescriptions, listMessages }
