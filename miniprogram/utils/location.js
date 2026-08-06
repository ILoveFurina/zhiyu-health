const { listServiceCities } = require('../services/directory')

/**
 * 就医位置（CONTEXT.md 词条）：用户在本次小程序使用期间确认的位置，
 * 用于同城医院筛选与距离计算。仅存活在模块级内存（本次会话），
 * 不落 storage、不进健康档案；用户可跳过或重新校正。
 */
let state = {
  latitude: null,
  longitude: null,
  name: '',
  skipped: false, // 启动确认被跳过后不再询问，距离随之隐藏
  city: null, // 当前服务城市 { city_code, city_name }，由位置与服务城市接口共同决定
}
let cityPromise = null // ensureCity 并发去重，避免多页面首请求重复调服务城市接口

/** 已确认位置的坐标（供目录接口按距离排序）；未确认时返回空对象。 */
function getCoords() {
  if (state.latitude == null || state.longitude == null) return {}
  return { lat: state.latitude, lng: state.longitude }
}

function hasLocation() {
  return state.latitude != null
}

function isSkipped() {
  return state.skipped
}

function markSkipped() {
  state.skipped = true
}

function getCity() {
  return state.city
}

/** my.chooseLocation 包装：支付宝返回 { latitude, longitude, name, address }；用户取消或失败时静默 resolve null。 */
function chooseLocation() {
  return new Promise((resolve) => {
    my.chooseLocation({
      success: (res) => {
        state.latitude = res.latitude
        state.longitude = res.longitude
        state.name = res.name || ''
        state.skipped = false
        state.city = null // 位置变更后必须重新解析当前城市
        resolve({ latitude: res.latitude, longitude: res.longitude, name: state.name })
      },
      fail: () => resolve(null),
    })
  })
}

/**
 * 当前服务城市：会话内已解析则直接返回；否则带坐标（如有）调服务城市接口，
 * 取返回列表第一项作为当前城市并缓存。城市由后端院区数据动态聚合，前端不写死任何城市。
 * 无服务城市时 resolve null，调用方按空态处理。
 */
function ensureCity() {
  if (state.city) return Promise.resolve(state.city)
  if (cityPromise) return cityPromise
  cityPromise = listServiceCities(getCoords())
    .then((cities) => {
      state.city = cities && cities.length > 0 ? cities[0] : null
      return state.city
    })
    .finally(() => {
      cityPromise = null
    })
  return cityPromise
}

/** 重新校正位置：重新选择位置成功后重新解析当前城市；取消时保持原状。 */
function relocate() {
  return chooseLocation().then((picked) => (picked ? ensureCity() : null))
}

module.exports = {
  getCoords,
  hasLocation,
  isSkipped,
  markSkipped,
  getCity,
  chooseLocation,
  ensureCity,
  relocate,
}
