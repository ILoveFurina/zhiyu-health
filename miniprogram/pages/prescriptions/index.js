const { ensureLogin } = require('../../utils/auth')
const { listPrescriptions } = require('../../services/patient-care')
const { createDrugOrder } = require('../../services/drug-orders')

Page({
  data: { loading: true, prescriptions: [], orderingId: null },
  onShow() { ensureLogin().then(() => this.load()) },
  load() {
    this.setData({ loading: true })
    listPrescriptions()
      .then((prescriptions) => this.setData({
        prescriptions: prescriptions.map((prescription) => ({
          ...prescription,
          items: prescription.items.map((item) => ({ ...item, quantity: 1 })),
        })),
      }))
      .catch(() => my.showToast({ content: '电子处方加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },
  order(e) {
    const id = e.currentTarget.dataset.id
    const prescription = this.data.prescriptions.find((item) => item.id === id)
    if (!prescription) return
    this.setData({ orderingId: id })
    const items = prescription.items.map((item) => ({
      medication_id: item.medication_id,
      quantity: item.quantity,
    }))
    createDrugOrder(id, items)
      .then(() => {
        my.showToast({ content: '下单成功', type: 'success' })
        my.navigateTo({ url: '/pages/drug-orders/index' })
      })
      .catch(() => my.showToast({ content: '下单失败，请检查库存后重试', type: 'fail' }))
      .finally(() => this.setData({ orderingId: null }))
  },
  changeQuantity(e) {
    const prescriptionId = e.currentTarget.dataset.prescriptionId
    const medicationId = e.currentTarget.dataset.medicationId
    const quantity = Math.max(1, Number(e.detail.value) || 1)
    const prescriptions = this.data.prescriptions.map((prescription) => ({
      ...prescription,
      items: prescription.id === prescriptionId
        ? prescription.items.map((item) => (
          item.medication_id === medicationId ? { ...item, quantity } : item
        ))
        : prescription.items,
    }))
    this.setData({ prescriptions })
  },
  openOrders() { my.navigateTo({ url: '/pages/drug-orders/index' }) },
})
