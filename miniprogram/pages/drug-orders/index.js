const { ensureLogin } = require('../../utils/auth')
const { listDrugOrders, cancelDrugOrder } = require('../../services/drug-orders')

Page({
  data: { loading: true, orders: [], cancellingId: null },
  onShow() { ensureLogin().then(() => this.load()) },
  load() {
    this.setData({ loading: true })
    listDrugOrders()
      .then((orders) => this.setData({ orders }))
      .catch(() => my.showToast({ content: '药品订单加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },
  cancel(e) {
    const id = e.currentTarget.dataset.id
    my.confirm({
      title: '取消订单',
      content: '确认取消该待支付订单吗？库存将自动返还。',
      success: (result) => {
        if (!result.confirm) return
        this.setData({ cancellingId: id })
        cancelDrugOrder(id)
          .then(() => {
            my.showToast({ content: '订单已取消', type: 'success' })
            this.load()
          })
          .catch(() => my.showToast({ content: '取消失败，请刷新后重试', type: 'fail' }))
          .finally(() => this.setData({ cancellingId: null }))
      },
    })
  },
})
