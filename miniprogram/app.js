const { ensureLogin } = require('./utils/auth')
const { detectRuntimeApiBase } = require('./utils/config')

App({
  globalData: {
    patient: null,
  },

  onLaunch() {
    // 探活本机 server-java，刷新 apiBaseUrl 缓存供下次启动使用（见 utils/config.js）
    detectRuntimeApiBase()
    // 免注册 mock 登录（Mock 边界）：静默换取患者身份
    ensureLogin().catch(() => {})
  },
})
