const { request } = require('../utils/request')

/**
 * 票 88（ADR-0035）：药品订单 C 端接口。确认页每次进入/提交实时拉取价格库存，
 * 不信任聊天卡片旧数据；下单失败（409 库存不足/处方已有活跃订单）由页面 toast 后端 detail。
 */

// 处方药确认页预览：返回处方医生/日期/来源医院/院区/锁定院区药房/明细数量与药房配送报价
const previewDrugOrder = (prescriptionId) => request({
  url: '/c/drug-orders/preview',
  data: { prescription_id: prescriptionId },
})

// OTC 候选药房：items 形如 "medication_id:quantity" 逗号列表；已授权定位带 lng/lat 按距离升序，
// 未授权不传坐标，服务端按医院/院区稳定序返回且不下发距离
const listOtcCandidates = ({ items, lng, lat }) => request({
  url: '/c/drug-orders/otc-candidates',
  data: {
    items,
    ...(lng != null && lat != null ? { lng, lat } : {}),
  },
})

// 药房 OTC 目录（票 95，只读浏览）：各院区药房在售 OTC 的价格/库存分组视图；
// 已授权定位带 lng/lat 按距离升序，未授权不传坐标，服务端按医院/院区稳定序返回且不下发距离
const listPharmacyOtcCatalog = ({ lng, lat } = {}) => request({
  url: '/c/pharmacy-otc-catalog',
  data: {
    ...(lng != null && lat != null ? { lng, lat } : {}),
  },
})

// 下单：处方药 { prescription_id, pickup_method, receiver_*? }；
// OTC { pharmacy_id, items: [{ medication_id, quantity }], pickup_method, receiver_*? }
const createDrugOrder = (payload) => request({
  url: '/c/drug-orders',
  method: 'POST',
  data: payload,
})

const listDrugOrders = () => request({ url: '/c/drug-orders' })

// 订单详情：价格快照/履约时间线/虚构承运方与单号/脱敏收货信息/自取地址快照
const getDrugOrder = (orderId) => request({ url: `/c/drug-orders/${orderId}` })

const cancelDrugOrder = (orderId) => request({
  url: `/c/drug-orders/${orderId}/cancel`,
  method: 'POST',
})

const payDrugOrder = (orderId) => request({
  url: `/c/drug-orders/${orderId}/pay`,
  method: 'POST',
})

module.exports = {
  previewDrugOrder,
  listOtcCandidates,
  listPharmacyOtcCatalog,
  createDrugOrder,
  listDrugOrders,
  getDrugOrder,
  cancelDrugOrder,
  payDrugOrder,
}
