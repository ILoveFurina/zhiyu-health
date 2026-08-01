const { request } = require('../utils/request')

const createDrugOrder = (prescriptionId, items) => request({
  url: '/c/drug-orders',
  method: 'POST',
  data: { prescription_id: prescriptionId, items },
})

const listDrugOrders = () => request({ url: '/c/drug-orders' })

const cancelDrugOrder = (orderId) => request({
  url: `/c/drug-orders/${orderId}/cancel`,
  method: 'POST',
})

module.exports = { createDrugOrder, listDrugOrders, cancelDrugOrder }
