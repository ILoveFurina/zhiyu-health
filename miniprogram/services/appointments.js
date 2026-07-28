const { request } = require('../utils/request')

function listAppointments() {
  return request({ url: '/c/appointments' })
}

function cancelAppointment(appointmentId) {
  return request({ url: `/c/appointments/${appointmentId}/cancel`, method: 'POST' })
}

module.exports = { listAppointments, cancelAppointment }
