const { ensureLogin } = require('../../utils/auth')

const INTERPRETATION_KEYWORDS = ['解读', '报告', '处方']

/** 自动档按意图分配：导诊 low，报告/处方解读 high。 */
function scenarioFor(content) {
  return INTERPRETATION_KEYWORDS.some((keyword) => content.includes(keyword))
    ? 'interpretation'
    : 'triage'
}

// 票 49：关键词自动定位路由（LOCATION_KEYWORDS）已移除——自助找医院收敛到
// AI挂号助手主卡与首页宫格；Agent 侧 hospital_recommendations 渲染（hospital-card）保留。
const hospitalRoutingMethods = {
  sendText(content) {
    if (!content) return
    ensureLogin()
      .then(() => this.startRound(content))
      .catch(() => my.showToast({ content: '登录失败，请稍后重试', type: 'fail' }))
  },

  onHospitalSelected(selection) {
    const { hospitalId, name } = selection
    if (!hospitalId) {
      this.sendText('我想找医院，请帮我看看附近有哪些科室')
      return
    }
    this.sendText(`我对${name}感兴趣（hospital_id: ${hospitalId}），请帮我看看这家医院有哪些科室和医生`)
  },
}

module.exports = { hospitalRoutingMethods, scenarioFor }
