const { apiBaseUrl } = require('../../utils/config')

Page({
  data: {
    loading: false,
    overall: '等待检测',
    services: [],
  },

  onLoad() {
    this.refreshHealth()
  },

  refreshHealth() {
    this.setData({ loading: true })
    wx.request({
      url: `${apiBaseUrl}/health`,
      success: ({ statusCode, data }) => {
        if (statusCode !== 200) {
          this.showFailure()
          return
        }
        const labels = {
          postgres: 'PostgreSQL + pgvector',
          redis: 'Redis',
          neo4j: 'Neo4j',
        }
        this.setData({
          overall: data.status === 'ok' ? '全部正常' : '部分异常',
          services: Object.keys(labels).map((name) => ({
            name,
            label: labels[name],
            status: data.services[name].status,
          })),
        })
      },
      fail: () => this.showFailure(),
      complete: () => this.setData({ loading: false }),
    })
  },

  showFailure() {
    this.setData({ overall: '无法连接云端服务', services: [] })
    wx.showToast({ title: 'Health 接口连接失败', icon: 'none' })
  },
})
