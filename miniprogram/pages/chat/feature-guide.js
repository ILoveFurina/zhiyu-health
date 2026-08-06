// AI 诊室引导语仅为客户端 UI，不经 SSE、不持久化、不带免责声明。
const TRIAGE_GREETING = '请描述您的不适，我帮您判断该挂什么科'

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
      content: TRIAGE_GREETING,
      disclaimer: '',
    }
    this.setData({ messages: [...this.data.messages, message], anchorId: 'thread-bottom' })
  },
}

module.exports = { featureGuideMethods }
