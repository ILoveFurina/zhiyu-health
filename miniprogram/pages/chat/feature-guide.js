// 智能导诊引导卡仅为客户端 UI，不经 SSE、不持久化、不带免责声明。
const TRIAGE_GUIDE = {
  title: '智能导诊',
  subtitle: '描述您的症状，我帮您找到该挂的科室',
  steps: ['① 描述症状', '② 推荐科室', '③ 查号源预约'],
  // 快捷症状 chips：label 展示、text 点击后直接发送（复用 sendPrompt → sendText）
  chips: [
    { label: '头痛发热', text: '我头痛发热，该挂什么科' },
    { label: '咳嗽咽痛', text: '我咳嗽咽痛，该挂什么科' },
    { label: '皮肤问题', text: '我皮肤长痘出油，该挂什么科' },
    { label: '肠胃不适', text: '我肠胃不适，该挂什么科' },
  ],
}

const featureGuideMethods = {
  onBubbleTap(e) {
    if (this.data.sending) return
    const action = e.currentTarget.dataset.action
    if (action === 'triage') this.enterTriage()
    else if (action === 'report') {
      if (!this.data.currentProfile) {
        my.showToast({ content: '请先创建健康档案', type: 'none' })
        this.startHealthProfile()
      } else this.openReportPicker()
    }
    else if (action === 'skin') {
      if (!this.data.currentProfile) {
        my.showToast({ content: '请先创建健康档案', type: 'none' })
        this.startHealthProfile()
      } else this.openSkinPicker()
    }
    else if (action === 'diet') {
      // 饮食场景差异化（票 16）：无激活档案时仍可分析，仅缺个性化提醒句，故不强制建档。
      this.openDietPicker()
    }
    else if (action === 'tongue') {
      // 舌苔中医辨证（票 17，ADR-0024）：无档案差异化需求，调理不出药材，不强制建档。
      this.openTonguePicker()
    }
    else if (action === 'pillbox') {
      // 拍药盒（票 51，ADR-0028）：说明书为通用药品知识流，不读档案不做个性化禁忌，不强制建档。
      this.openPillboxPicker()
    }
  },

  enterTriage() {
    const message = {
      id: ++this._msgSeq,
      role: 'assistant',
      kind: 'feature_guide',
      content: '',
      guide: TRIAGE_GUIDE,
      disclaimer: '',
    }
    this.setData({ messages: [...this.data.messages, message], anchorId: 'thread-bottom' })
  },

  /**
   * 消费首页「智能导诊」入口的交棒标志（票 62，switchTab 不能带参，经 globalData 传递）；
   * sending 时不消费、保留到下次 onShow（与 report-composer 同一约定）。
   */
  consumeTriageEntry() {
    const app = getApp()
    if (!app.globalData.pendingTriageEntry) return
    if (this.data.sending) return
    app.globalData.pendingTriageEntry = false
    this.enterTriage()
  },
}

module.exports = { featureGuideMethods }
