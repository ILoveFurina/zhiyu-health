const { ensureLogin } = require('../../utils/auth')
const { listPrescriptions } = require('../../services/patient-care')
const { SOURCE_TYPES, SOURCE_TYPE_LABELS, STATUSES } = require('../../utils/prescription')

Page({
  data: { loading: true, prescriptions: [], skelItems: [1, 2, 3] },
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
          // 票 88：已核销处方（支付成功一次性消费）不可再次购药；字段名以服务端下发为准，做布尔归一
          is_redeemed: prescription.redeemed === true || Boolean(prescription.redeemed_at),
        })),
      }))
      .catch(() => my.showToast({ content: '电子处方加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },
  // 票 88：「去购药」跳统一购药确认页（与 Agent 购药预览卡同一入口），
  // 确认页实时校验价格库存后才建单；配药数量由医生开方决定，患者不可修改
  goBuy(e) {
    const id = e.currentTarget.dataset.id
    my.navigateTo({ url: `/pages/drug-order-confirm/index?source=PRESCRIPTION&prescription_id=${id}` })
  },
  openOrders() { my.navigateTo({ url: '/pages/drug-orders/index' }) },
  // 驳回引导出口：与首页在线问诊/预约挂号入口一致
  goConsult() { my.navigateTo({ url: '/pages/consult/entry/index' }) },
  goBooking() { my.navigateTo({ url: '/pages/booking/hospitals/index' }) },
})
