const { ensureLogin } = require('../../utils/auth')
const { listPrescriptions } = require('../../services/patient-care')

Page({
  data: { loading: true, prescriptions: [], orderingId: null, orderedId: null },
  onShow() { ensureLogin().then(() => this.load()) },
  load() {
    this.setData({ loading: true })
    listPrescriptions()
      .then((prescriptions) => this.setData({ prescriptions }))
      .catch(() => my.showToast({ content: '电子处方加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },
  order(e) {
    const id = e.currentTarget.dataset.id
    this.setData({ orderingId: id })
    setTimeout(() => {
      this.setData({ orderingId: null, orderedId: id })
      my.showToast({ content: 'Mock 下单成功', type: 'success' })
    }, 500)
  },
})
