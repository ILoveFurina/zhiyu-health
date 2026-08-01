const { request } = require('../utils/request')

/** 医院/科室/医生/排班目录（票 41 API，票 42 阶段二挂号流消费）。 */
function listHospitals(coords = {}) {
  const hasCoords = coords.lat != null && coords.lng != null
  const query = hasCoords ? `?lat=${coords.lat}&lng=${coords.lng}` : ''
  return request({ url: `/c/hospitals${query}` })
}

function listDepartments(hospitalId) {
  return request({ url: `/c/hospitals/${hospitalId}/departments` })
}

function listDoctors(departmentId) {
  return request({ url: `/c/departments/${departmentId}/doctors` })
}

function listSchedules(doctorId) {
  return request({ url: `/c/doctors/${doctorId}/schedules` })
}

function createAppointment(scheduleId) {
  return request({ url: '/c/appointments', method: 'POST', data: { schedule_id: scheduleId } })
}

module.exports = { listHospitals, listDepartments, listDoctors, listSchedules, createAppointment }
