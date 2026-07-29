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
  replaceAllergies,
}
