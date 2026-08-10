const { ensureLogin } = require('../../utils/auth')
const { listPharmacyOtcCatalog } = require('../../services/drug-orders')
const { getCoords, hasLocation } = require('../../utils/location')
const { formatDistance } = require('../../utils/drug-order')

/** 金额统一格式化为两位小数；后端数值缺失时返回空串，页面不伪造价格。 */
function money(value) {
  const n = Number(value)
  return Number.isFinite(n) ? n.toFixed(2) : ''
}

/**
 * 药房 OTC 目录（票 95）：只读浏览各院区药房在售 OTC 的价格/库存，不下单、
 * 不直跳购药确认页。每味药「去买」仅把「我想买<药品名>」（不带数量，ADR-0032 硬边界：
 * 数量必须由用户在对话中明确给出）经 globalData 交棒预填进聊天输入框，用户自行补数量并发送。
 * 本页不是 AI 产出，不挂「仅供参考」免责声明。
 */
Page({
  data: {
    loading: true,
    located: false, // 已授权定位：药房按距离升序并展示距离徽标；未授权按返回稳定序且不显示距离
    pharmacies: [],
  },

  onLoad() {
    ensureLogin().then(() => this.load())
  },

  load() {
    this.setData({ loading: true })
    // 定位获取与失败降级镜像 drug-order-confirm：无定位照常展示、隐藏距离
    const located = hasLocation()
    const coords = getCoords()
    return listPharmacyOtcCatalog(coords)
      .then((res) => {
        const pharmacies = ((res && res.pharmacies) || []).map((pharmacy) => ({
          ...pharmacy,
          distance_text: located ? formatDistance(pharmacy.distance_meters) : '',
          items: (pharmacy.items || []).map((item) => ({
            ...item,
            price_text: money(item.price),
            spec_text: [item.generic_name, item.specification].filter(Boolean).join(' · '),
            out_of_stock: !item.stock || item.stock <= 0,
          })),
        }))
        this.setData({ located, pharmacies })
      })
      .catch((err) => {
        my.showToast({ content: err.detail || '药房目录加载失败', type: 'fail' })
      })
      .finally(() => this.setData({ loading: false }))
  },

  /** 「去买」：只交棒预填话术并切到聊天 tab，不自动发送、不带数量。 */
  buy(e) {
    const name = e.currentTarget.dataset.name
    if (!name) return
    getApp().globalData.pendingDrugPurchasePrompt = `我想买${name}`
    my.switchTab({ url: '/pages/chat/index' })
  },
})
