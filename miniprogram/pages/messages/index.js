const { ensureLogin } = require('../../utils/auth')
const { listMessages } = require('../../services/patient-care')

Page({
  data: { loading: true, messages: [] },
  onShow() {
    ensureLogin().then(() => listMessages())
      .then((messages) => this.setData({ messages }))
      .catch(() => my.showToast({ content: '消息加载失败', type: 'fail' }))
      .finally(() => this.setData({ loading: false }))
  },
})
