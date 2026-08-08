const { ensureLogin } = require('../../utils/auth')
const { listPrescriptions } = require('../../services/patient-care')
const { createDrugOrder } = require('../../services/drug-orders')
const { SOURCE_TYPES, SOURCE_TYPE_LABELS, STATUSES } = require('../../utils/prescription')

Page({
  data: { loading: true, prescriptions: [], orderingId: null, skelItems: [1, 2, 3] },
  onShow() { ensureLogin().then(() => this.load()) },
  load() {
    this.setData({ loading: true })
    listPrescriptions()
      .then((prescriptions) => this.setData({
        prescriptions: prescriptions.map((prescription) => ({
          ...prescription,
          // 仅在线问诊处方展示来源标签；date 由服务端按来源语义给出（在线=问诊发生日期），端侧不加工
          source_label: prescription.source_type === SOURCE_TYPES.online_consultation
            ? SOURCE_TYPE_LABELS.ONLINE_CONSULTATION
            : '',
          // 票 60：列表回全部状态，状态徽标与卡内分支按布尔渲染，axml 不写魔法值
          is_approved: prescription.status === STATUSES.approved,
          is_pending: prescription.status === STATUSES.pending,
          is_rejected: prescription.status === STATUSES.rejected,
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
  // 驳回引导出口：与首页在线问诊/预约挂号入口一致
  goConsult() { my.navigateTo({ url: '/pages/consult/entry/index' }) },
  goBooking() { my.navigateTo({ url: '/pages/booking/hospitals/index' }) },
})
