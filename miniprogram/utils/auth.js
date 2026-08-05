const { apiBaseUrl } = require('./config')

function getToken() {
  const cached = my.getStorageSync({ key: 'token' })
  return cached.data || ''
}

// 共享登录 Promise：app.onLaunch 与各页面首次请求并发时，保证 mock-login 只发一次，
// 且业务请求必在 token 落 storage 后才发出（修复启动期 401 subject=null 竞态）。
let loginPromise = null

/**
 * 免注册 mock 登录（Mock 边界）：无令牌时调用后端取或建患者身份。
 * 返回 Promise<token>；已有缓存令牌或已有进行中的登录时复用同一 Promise。
 */
function ensureLogin() {
  if (loginPromise) return loginPromise
  const cached = getToken()
  if (cached) {
    return Promise.resolve(cached)
  }
  loginPromise = new Promise((resolve, reject) => {
    my.request({
      url: `${apiBaseUrl}/c/auth/mock-login`,
      method: 'POST',
      data: { nickname: '演示患者' },
      headers: { 'Content-Type': 'application/json' },
      success: (res) => {
        if (res.status === 200 && res.data && res.data.token) {
          my.setStorageSync({ key: 'token', data: res.data.token })
          my.setStorageSync({ key: 'patient', data: res.data.patient })
          resolve(res.data.token)
        } else {
          // 登录失败清空 pending，允许后续重试
          loginPromise = null
          reject(new Error(`登录失败（${res.status}）`))
        }
      },
      fail: () => {
        loginPromise = null
        reject(new Error('无法连接服务器'))
      },
    })
  })
  return loginPromise
}

module.exports = { ensureLogin, getToken }
