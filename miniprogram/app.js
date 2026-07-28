const { ensureLogin } = require('./utils/auth')

App({
  globalData: {
    patient: null,
  },

  onLaunch() {
    // 免注册 mock 登录（Mock 边界）：静默换取患者身份
    ensureLogin().catch(() => {})
  },
})
