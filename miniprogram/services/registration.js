const { listCityHospitals } = require('./directory')
const { ensureCity, getCoords } = require('../utils/location')

/**
 * AI挂号助手主卡数据装配：当前城市 + 最近 3 家平台医院 + 真实总数。
 * 首页与对话空态共用（票 49 决策：主卡不复制业务状态），距离在 js 侧格式化为一位小数。
 * 无当前城市时 resolve 空结果，由调用方决定空态展示。
 */
function loadRegistrationSummary() {
  return ensureCity().then((city) => {
    if (!city) return { cityName: '', hospitals: [], total: 0 }
    return listCityHospitals(city.city_code, getCoords()).then((list) => {
      const hospitals = (list || []).map((item) => ({
        ...item,
        distance_text: item.distance_km != null ? Number(item.distance_km).toFixed(1) : '',
      }))
      return { cityName: city.city_name, hospitals: hospitals.slice(0, 3), total: hospitals.length }
    })
  })
}

module.exports = { loadRegistrationSummary }
