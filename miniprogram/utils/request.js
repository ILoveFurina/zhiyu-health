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
          reject(new Error(`请求失败（${res.status}）`))
        }
      },
      fail: () => reject(new Error('无法连接服务器')),
    })
  })
}

module.exports = { request }
