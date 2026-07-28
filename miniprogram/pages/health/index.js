const { request } = require('../../utils/request')

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
    request({ url: '/health' })
      .then((data) => {
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
            status: (data.services[name] && data.services[name].status) || 'error',
          })),
        })
      })
      .catch(() =>
        this.setData({ overall: '后端不可达', services: [] })
      )
      .finally(() => this.setData({ loading: false }))
  },
})
