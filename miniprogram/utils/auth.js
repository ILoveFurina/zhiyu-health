const { apiBaseUrl } = require('./config')

function getToken() {
  const cached = my.getStorageSync({ key: 'token' })
  return cached.data || ''
}

/**
 * 免注册 mock 登录（Mock 边界）：无令牌时调用后端取或建患者身份。
 * 返回 Promise<token>；已有缓存令牌时直接 resolve。
 */
function ensureLogin() {
  const cached = getToken()
  if (cached) {
    return Promise.resolve(cached)
  }
  return new Promise((resolve, reject) => {
    my.request({
      url: `${apiBaseUrl}/c/auth/mock-login`,
      method: 'POST',
      data: {},
      headers: { 'Content-Type': 'application/json' },
      success: (res) => {
        if (res.status === 200 && res.data && res.data.token) {
          my.setStorageSync({ key: 'token', data: res.data.token })
          my.setStorageSync({ key: 'patient', data: res.data.patient })
          resolve(res.data.token)
        } else {
          reject(new Error(`登录失败（${res.status}）`))
        }
      },
      fail: () => reject(new Error('无法连接服务器')),
    })
  })
}

module.exports = { ensureLogin, getToken }
