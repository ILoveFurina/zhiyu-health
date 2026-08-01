const { request } = require('../utils/request')

function listAppointments() {
  return request({ url: '/c/appointments' })
}

function cancelAppointment(appointmentId) {
  return request({ url: `/c/appointments/${appointmentId}/cancel`, method: 'POST' })
}

function payAppointment(appointmentId) {
  return request({ url: `/c/appointments/${appointmentId}/payment/pay`, method: 'POST' })
}

module.exports = { listAppointments, cancelAppointment, payAppointment }
