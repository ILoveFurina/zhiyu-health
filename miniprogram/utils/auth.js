const { apiBaseUrl } = require('./config')

function getToken() {
  const cached = my.getStorageSync({ key: 'token' })
  return cached.data || ''
}

// JWT 有效期 12h（server-java zhiyu.patient-token-expire-minutes=720）。
// 缓存令牌只查"存在"会带着过期令牌永久 401（真机隔日必现），按 exp 留 30s 余量判过期。
function isTokenUsable(token) {
  const parts = token.split('.')
  if (parts.length !== 3) return false
  try {
    const payload = JSON.parse(base64UrlDecode(parts[1]))
    return typeof payload.exp === 'number' && payload.exp * 1000 > Date.now() + 30000
  } catch (err) {
    return false
  }
}

// 小程序运行时无 atob/Buffer；exp 为数字、JWT 骨架是 ASCII，逐字节解码不影响 JSON.parse。
const B64_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
function base64UrlDecode(input) {
  const normalized = input.replace(/-/g, '+').replace(/_/g, '/')
  let output = ''
  let buffer = 0
  let bits = 0
  for (let i = 0; i < normalized.length; i += 1) {
    const ch = normalized.charAt(i)
    if (ch === '=') break
    const value = B64_ALPHABET.indexOf(ch)
    if (value === -1) throw new Error('非法 base64 字符')
    buffer = (buffer << 6) | value
    bits += 6
    if (bits >= 8) {
      bits -= 8
      output += String.fromCharCode((buffer >> bits) & 0xff)
      // 只保留未消费的低位，防止 buffer 越移越长溢出 32 位按位运算
      buffer &= (1 << bits) - 1
    }
  }
  return output
}

// 共享登录 Promise：app.onLaunch 与各页面首次请求并发时，保证 mock-login 只发一次，
// 且业务请求必在 token 落 storage 后才发出（修复启动期 401 subject=null 竞态）。
let loginPromise = null

/**
 * 免注册 mock 登录（Mock 边界）：无令牌或令牌过期时调用后端取或建患者身份。
 * 返回 Promise<token>；已有可用缓存令牌或已有进行中的登录时复用同一 Promise。
 */
function ensureLogin() {
  if (loginPromise) return loginPromise
  const cached = getToken()
  // 过期令牌必须落到登录分支重取，否则所有请求带着过期令牌反复 401
  if (cached && isTokenUsable(cached)) {
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
