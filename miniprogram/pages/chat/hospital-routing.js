const { ensureLogin } = require('../../utils/auth')

const INTERPRETATION_KEYWORDS = ['解读', '报告', '处方']
const LOCATION_KEYWORDS = ['附近', '就近', '最近', '周边', '哪里有医院', '找医院']

/** 自动档按意图分配：导诊 low，报告/处方解读 high。 */
function scenarioFor(content) {
  return INTERPRETATION_KEYWORDS.some((keyword) => content.includes(keyword))
    ? 'interpretation'
    : 'triage'
}

function wantsNearbyHospital(content) {
  return LOCATION_KEYWORDS.some((keyword) => content.includes(keyword))
}

const hospitalRoutingMethods = {
  sendText(content) {
    if (!content) return
    ensureLogin()
      .then(() => {
        if (wantsNearbyHospital(content)) {
          this._locateAndSend(content)
          return
        }
        this.startRound(content)
      })
      .catch(() => my.showToast({ content: '登录失败，请稍后重试', type: 'fail' }))
  },

  /** 定位拒绝时仍发送请求，由 Agent 返回手动选区引导。 */
  _locateAndSend(content) {
    my.getLocation({
      type: 1,
      success: (res) =>
        this.startRound(content, { longitude: res.longitude, latitude: res.latitude }),
      fail: () => {
        my.showToast({ content: '未获取到定位，将按区域推荐', type: 'none' })
        this.startRound(content, { longitude: undefined, latitude: undefined })
      },
    })
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
