const { request } = require('../utils/request')

/**
 * 目录与号源（票 49）：城市 → 医院/标准科室 → 院区/实际科室 → 医生 → 14 天号源 → 挂号。
 * 当前城市是医院与号源查询的硬边界，所有列表接口都必须带 city_code（由 utils/location 解析）。
 */
function coordsQuery(coords = {}) {
  return coords.lat != null && coords.lng != null ? `lat=${coords.lat}&lng=${coords.lng}` : ''
}

/** 动态服务城市列表；带坐标时按最近院区排序，调用方取第一项作为当前城市。 */
function listServiceCities(coords = {}) {
  const query = coordsQuery(coords)
  return request({ url: `/c/service-cities${query ? `?${query}` : ''}` })
}

function listCityHospitals(cityCode, coords = {}) {
  const coordsPart = coordsQuery(coords)
  return request({ url: `/c/hospitals?city_code=${cityCode}${coordsPart ? `&${coordsPart}` : ''}` })
}

function listCampuses(hospitalId, coords = {}) {
  const query = coordsQuery(coords)
  return request({ url: `/c/hospitals/${hospitalId}/campuses${query ? `?${query}` : ''}` })
}

function listCampusDepartments(campusId) {
  return request({ url: `/c/campuses/${campusId}/departments` })
}

/** 城市级「科类 → 标准科室」目录。 */
function listStandardDepartments(cityCode) {
  return request({ url: `/c/standard-departments?city_code=${cityCode}` })
}

/**
 * 跨医院标准科室号源（科室号源卡）：统一返回今天起连续 14 天，
 * 指定 date（yyyy-MM-dd）时医生只带当日排班；有号医生优先，无号医生保留（bookable=false）。
 */
function listStandardDepartmentSlots(standardDepartmentId, { cityCode, lat, lng, date } = {}) {
  let query = `city_code=${cityCode}`
  if (lat != null && lng != null) query += `&lat=${lat}&lng=${lng}`
  if (date) query += `&date=${date}`
  return request({ url: `/c/standard-departments/${standardDepartmentId}/slots?${query}` })
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

module.exports = {
  listServiceCities,
  listCityHospitals,
  listCampuses,
  listCampusDepartments,
  listStandardDepartments,
  listStandardDepartmentSlots,
  listDoctors,
  listSchedules,
  createAppointment,
}
