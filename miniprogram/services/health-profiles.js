const { request } = require('../utils/request')

function listProfiles() {
  return request({ url: '/c/health-profiles' })
}

function currentProfile() {
  return request({ url: '/c/health-profiles/current' })
}

function createProfile(data) {
  return request({ url: '/c/health-profiles', method: 'POST', data })
}

function activateProfile(profileId) {
  return request({ url: `/c/health-profiles/${profileId}/activate`, method: 'POST' })
}

function listTimeline(profileId) {
  return request({ url: `/c/health-profiles/${profileId}/timeline` })
}

/** 健康概要（票 61）：血型类最新值 + 有数据的数值指标 + 最近报告。 */
function getOverview(profileId) {
  return request({ url: `/c/health-profiles/${profileId}/overview` })
}

/** 单指标历次观测（指标卡展开明细用）。 */
function listObservations(profileId, metricCode) {
  return request({ url: `/c/health-profiles/${profileId}/observations?metric_code=${metricCode}` })
}

function replaceAllergies(profileId, allergies) {
  return request({
    url: `/c/health-profiles/${profileId}/allergies`,
    method: 'PUT',
    data: { allergies },
  })
}

module.exports = {
  listProfiles,
  currentProfile,
  createProfile,
  activateProfile,
  listTimeline,
  getOverview,
  listObservations,
  replaceAllergies,
}
