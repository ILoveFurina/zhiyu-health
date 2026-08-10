const { ensureLogin } = require('../../utils/auth')
const {
  previewDrugOrder,
  listOtcCandidates,
  createDrugOrder,
} = require('../../services/drug-orders')
const { getCoords, hasLocation } = require('../../utils/location')
const {
  PICKUP_METHODS,
  SOURCES,
  SOURCE_LABELS,
  formatDistance,
} = require('../../utils/drug-order')

/** 金额统一格式化为两位小数；后端数值缺失时返回空串，页面不伪造价格。 */
function money(value) {
  const n = Number(value)
  return Number.isFinite(n) ? n.toFixed(2) : ''
}

/**
 * 统一购药确认页（票 88，ADR-0035）：Agent 购药预览卡与处方页「去购药」共同入口。
 * 每次进入实时拉取价格/库存/候选，不信任卡片旧数据；只有患者在本页确认才创建订单。
 * 入参：source=PRESCRIPTION&prescription_id= ；source=OTC&items=<URL 编码 JSON 明细>。
 */
Page({
  data: {
    loading: true,
    source: '',
    sourceLabel: '',
    isPrescription: false,
    // 处方药预览：处方医生/日期/来源医院/院区 + 锁定院区药房（患者不可更改）
    preview: null,
    // OTC：药品明细 + 可整单履约候选药房（不预选第一家，患者必须显式选择）
    items: [],
    pharmacies: [],
    selectedPharmacyId: null,
    located: false, // 已授权定位：候选按距离升序并展示距离；未授权按返回稳定序且不显示距离
    // 取药方式：默认院区自取（配送费 0、不收集收货信息），患者可显式切换配送到家
    pickupMethod: PICKUP_METHODS.pickup,
    isDelivery: false,
    receiverName: '',
    receiverPhone: '',
    receiverAddress: '',
    // 价格小结与药房信息（随药房选择/取药方式实时重算）
    pharmacyName: '',
    pharmacyMeta: '',
    medicationAmount: '',
    deliveryFee: '',
    totalAmount: '',
    estimatedText: '',
    pickupAddress: '',
    submitting: false,
  },

  onLoad(query) {
    const source = query && query.source === SOURCES.prescription
      ? SOURCES.prescription
      : SOURCES.otc
    this.prescriptionId = (query && query.prescription_id) || null
    // OTC 明细经 URL 携带仅作名称/规格展示，下单只透传 medication_id:quantity；
    // 价格与库存一律以 otc-candidates 接口实时返回为准
    this.orderItems = []
    if (source === SOURCES.otc && query && query.items) {
      try {
        this.orderItems = JSON.parse(decodeURIComponent(query.items)) || []
      } catch (_) {
        this.orderItems = []
      }
    }
    this.setData({
      source,
      sourceLabel: SOURCE_LABELS[source] || '',
      isPrescription: source === SOURCES.prescription,
    })
    ensureLogin().then(() => this.load())
  },

  load() {
    return this.data.isPrescription ? this.loadPrescription() : this.loadOtc()
  },

  loadPrescription() {
    this.setData({ loading: true })
    return previewDrugOrder(this.prescriptionId)
      .then((preview) => {
        this.setData({ preview })
        this.refreshSummary()
      })
      .catch((err) => {
        my.showToast({ content: err.detail || '处方预览加载失败', type: 'fail' })
      })
      .finally(() => this.setData({ loading: false }))
  },

  loadOtc() {
    this.setData({ loading: true })
    const located = hasLocation()
    const coords = getCoords()
    const itemsParam = this.orderItems
      .map((item) => `${item.medication_id}:${item.quantity}`)
      .join(',')
    return listOtcCandidates({ items: itemsParam, lng: coords.lng, lat: coords.lat })
      .then((res) => {
        const payload = res || {}
        const pharmacies = (payload.pharmacies || []).map((item) => ({
          ...item,
          distance_text: located ? formatDistance(item.distance_meters) : '',
        }))
        // 明细名称/规格以候选接口实时返回为准；接口未下发时回退 URL 携带的展示快照
        const displayItems =
          payload.items && payload.items.length > 0 ? payload.items : this.orderItems
        this.setData({ located, pharmacies, items: displayItems })
        this.refreshSummary()
      })
      .catch((err) => {
        my.showToast({ content: err.detail || '候选药房加载失败', type: 'fail' })
      })
      .finally(() => this.setData({ loading: false }))
  },

  /** 当前生效药房：处方药为处方锁定院区药房，OTC 为患者显式选中的候选。 */
  currentPharmacy() {
    if (this.data.isPrescription) {
      const p = this.data.preview
      if (!p) return null
      return {
        pharmacy_id: p.pharmacy_id,
        pharmacy_name: p.pharmacy_name,
        hospital_name: p.hospital_name,
        campus_name: p.campus_name,
        campus_address: p.campus_address,
        delivery_fee: p.delivery_fee,
        estimated_minutes: p.estimated_minutes,
        medication_amount: p.medication_amount,
      }
    }
    return (
      this.data.pharmacies.find(
        (item) => String(item.pharmacy_id) === String(this.data.selectedPharmacyId)
      ) || null
    )
  },

  /** 价格小结/取药方式联动重算：自取配送费恒为 0，配送取药房固定配送费。 */
  refreshSummary() {
    const pharmacy = this.currentPharmacy()
    const isDelivery = this.data.pickupMethod === PICKUP_METHODS.delivery
    const medicationAmount = pharmacy ? money(pharmacy.medication_amount) : ''
    const deliveryFee = pharmacy ? (isDelivery ? money(pharmacy.delivery_fee) : '0.00') : ''
    const totalAmount =
      medicationAmount !== '' && deliveryFee !== ''
        ? (Number(medicationAmount) + Number(deliveryFee)).toFixed(2)
        : ''
    this.setData({
      isDelivery,
      medicationAmount,
      deliveryFee,
      totalAmount,
      pharmacyName: pharmacy ? pharmacy.pharmacy_name || '' : '',
      pharmacyMeta: pharmacy
        ? [pharmacy.hospital_name, pharmacy.campus_name].filter(Boolean).join(' · ')
        : '',
      estimatedText:
        pharmacy && pharmacy.estimated_minutes ? `预计约 ${pharmacy.estimated_minutes} 分钟送达` : '',
      pickupAddress: pharmacy ? pharmacy.campus_address || '' : '',
    })
  },

  selectPharmacy(e) {
    this.setData({ selectedPharmacyId: e.currentTarget.dataset.id })
    this.refreshSummary()
  },

  selectPickupMethod(e) {
    const method = e.currentTarget.dataset.method
    if (method !== PICKUP_METHODS.pickup && method !== PICKUP_METHODS.delivery) return
    this.setData({ pickupMethod: method })
    this.refreshSummary()
  },

  onReceiverNameInput(e) {
    this.setData({ receiverName: e.detail.value })
  },

  onReceiverPhoneInput(e) {
    this.setData({ receiverPhone: e.detail.value })
  },

  onReceiverAddressInput(e) {
    this.setData({ receiverAddress: e.detail.value })
  },

  submit() {
    if (this.data.submitting || this.data.loading) return
    const { source, pickupMethod } = this.data
    const pharmacy = this.currentPharmacy()
    if (source === SOURCES.otc && !pharmacy) {
      my.showToast({ content: '请先选择履约药房', type: 'fail' })
      return
    }
    if (source === SOURCES.prescription && !this.prescriptionId) {
      my.showToast({ content: '缺少处方信息，请返回重试', type: 'fail' })
      return
    }
    const payload = { pickup_method: pickupMethod }
    if (pickupMethod === PICKUP_METHODS.delivery) {
      // 配送才采集一次性收货信息（快照归属本笔订单，不建地址簿）；自取不收集
      const receiverName = this.data.receiverName.trim()
      const receiverPhone = this.data.receiverPhone.trim()
      const receiverAddress = this.data.receiverAddress.trim()
      if (!receiverName || !receiverPhone || !receiverAddress) {
        my.showToast({ content: '请填写完整收货信息', type: 'fail' })
        return
      }
      if (!/^1\d{10}$/.test(receiverPhone)) {
        my.showToast({ content: '请填写 11 位手机号', type: 'fail' })
        return
      }
      payload.receiver_name = receiverName
      payload.receiver_phone = receiverPhone
      payload.receiver_address = receiverAddress
    }
    if (source === SOURCES.prescription) {
      payload.prescription_id = this.prescriptionId
    } else {
      payload.pharmacy_id = pharmacy.pharmacy_id
      payload.items = this.orderItems.map((item) => ({
        medication_id: item.medication_id,
        quantity: item.quantity,
      }))
    }
    this.setData({ submitting: true })
    createDrugOrder(payload)
      .then((order) => {
        my.showToast({ content: '下单成功', type: 'success' })
        // 下单结果卡由 server-java 落聊天，本端直接跳订单详情管理支付/履约
        my.redirectTo({ url: `/pages/drug-order-detail/index?id=${order.id}` })
      })
      .catch((err) => {
        // 409（库存不足/处方已有活跃订单）等：toast 后端 message 并刷新实时数据
        my.showToast({ content: err.detail || '下单失败，请稍后重试', type: 'fail' })
        this.load()
      })
      .finally(() => this.setData({ submitting: false }))
  },
})
