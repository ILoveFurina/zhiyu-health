const { apiBaseUrl } = require('./config')
const { ensureLogin, getToken } = require('./auth')

/** 带鉴权的 JSON 请求，返回 Promise<响应数据>。 */
function request({ url, method = 'GET', data, timeout = 30000 }) {
  // 先等登录态就绪，避免 app.onLaunch 的异步登录与页面首请求并发导致裸奔 401
  return ensureLogin()
    .then(() => {
      return new Promise((resolve, reject) => {
        my.request({
          url: `${apiBaseUrl}${url}`,
          method,
          data,
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${getToken()}`,
          },
          timeout,
          success: (res) => {
            if (res.status >= 200 && res.status < 300) {
              resolve(res.data)
            } else {
              // 附带后端 ApiException 错误体的 detail 文案（字符串或 {code,message}），
              // 供确认页等场景展示后端 message；err.message 保持原样，不影响既有调用方
              const err = new Error(`请求失败（${res.status}）`)
              err.status = res.status
              const detail = res.data && res.data.detail
              err.detail = typeof detail === 'string' ? detail : (detail && detail.message) || ''
              reject(err)
            }
          },
          fail: (res) => {
            // 支付宝 my.request 对 HTTP >= 400 也走 fail（error:19，响应体在 res.data，
            // 见支付宝官方文档错误码表）——fail 不等于断网。取出后端 ApiException 的
            // detail（如"请勿重复挂号"），与 success 分支非 2xx 路径同口径；
            // 真正的网络/跨域失败 res.data 为空，detail 落空由页面兜底文案承接。
            const err = new Error((res && res.errorMessage) || '无法连接服务器')
            let body = res && res.data
            if (typeof body === 'string') {
              try {
                body = JSON.parse(body)
              } catch (_) {
                body = null
              }
            }
            const detail = body && body.detail
            err.detail = typeof detail === 'string' ? detail : (detail && detail.message) || ''
            reject(err)
          },
        })
      })
    })
}

module.exports = { request }
