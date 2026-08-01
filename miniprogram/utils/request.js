const { apiBaseUrl } = require('./config')
const { getToken } = require('./auth')

/** 带鉴权的 JSON 请求，返回 Promise<响应数据>。 */
function request({ url, method = 'GET', data, timeout = 30000 }) {
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
      fail: () => reject(new Error('无法连接服务器')),
    })
  })
}

module.exports = { request }
