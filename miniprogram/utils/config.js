module.exports = {
  // 统一入口为业务后端；对话由它鉴权、审计后逐跳透传至 Agent 层。
  // 真机调试时临时改为开发机局域网 IP（真机上 localhost 是手机自己），调试完改回，勿提交机器特定地址。
  apiBaseUrl: 'https://localhost:8443/api',
}
