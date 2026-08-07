// 统一入口为业务后端；对话由它鉴权、审计后逐跳透传至 Agent 层。
// 双地址运行时选择：devtools 模拟器会把自己伪装成真机（platform/brand 均为 iPhone），
// 无法靠 getSystemInfo 区分，故改为"localhost 探活 + storage 缓存"——
// app.js onLaunch 每次启动探测本机 server-java，结果写入 storage 供下次启动同步读取：
//   模拟器（本机 8080 可达）→ LOCAL，WS 流式可用（需关闭"校验合法域名…HTTPS 证书"，README 第 4 节）；
//   真机（localhost 是手机自己，探活必失败）→ TUNNEL，对话因 cpolar 丢弃 WS 握手头自动降级 SSE
//   （docs/engineering-notes/wss-and-windows-service-pitfalls.md 第 6 节）。
// 真机预览需自架隧道（如 cpolar：cpolar http 8080），把分到的域名填到 TUNNEL_API_BASE_URL。
// 隧道域名属机器特定地址，只改本地，勿提交。
const TUNNEL_API_BASE_URL = 'https://YOUR-CPOLAR-DOMAIN/api'
const LOCAL_API_BASE_URL = 'http://localhost:8080/api'
const STORAGE_KEY = 'runtimeApiBaseUrl'

// 无缓存时（首次启动）默认 LOCAL：队友克隆后模拟器开箱即用；
// 真机首次启动探活后缓存 TUNNEL，重新进入小程序即走隧道。
const cached = my.getStorageSync({ key: STORAGE_KEY }).data

/** 每次启动探活本机 server-java，刷新缓存供下次启动使用（本次启动沿用缓存值）。 */
function detectRuntimeApiBase() {
  my.request({
    url: `${LOCAL_API_BASE_URL}/health`,
    timeout: 2000,
    success: (res) => {
      if (res.status === 200) my.setStorageSync({ key: STORAGE_KEY, data: LOCAL_API_BASE_URL })
    },
    fail: () => my.setStorageSync({ key: STORAGE_KEY, data: TUNNEL_API_BASE_URL }),
  })
}

module.exports = {
  apiBaseUrl: cached || TUNNEL_API_BASE_URL,
  detectRuntimeApiBase,
}
