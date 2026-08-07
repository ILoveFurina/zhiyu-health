const { getDraft, createConsultation } = require('../../../services/consultation')
const {
  SUMMARY_FIELDS,
  SUMMARY_FIELD_LABELS,
  DRAFT_STATUS_LABELS,
  TEXTS,
} = require('../../../utils/consultation')

/**
 * 病情摘要确认页（票 55）：展示预问诊草稿的摘要快照与建议科室，
 * 患者确认后创建在线问诊单并转等待接诊页；「继续调整」返回预问诊续聊。
 */
Page({
  data: {
    draftId: null,
    // 确认前仍停留在 PRECONSULTATION 步；确认提交后离开本页，由等待页推进进度
    progressStep: 'PRECONSULTATION',
    loading: true,
    draftStatusLabel: '',
    fields: [], // [{key, label, value}] 摘要字段行
    departmentName: '',
    disclaimer: '',
    canConfirm: false,
    confirmHint: '',
    submitting: false,
  },

  onLoad(query) {
    this.setData({ draftId: query && query.draftId })
  },

  onShow() {
    this.loadDraft()
  },

  loadDraft() {
    if (!this.data.draftId) return
    this.setData({ loading: true })
    getDraft(this.data.draftId)
      .then((res) => {
        const draft = res && res.draft
        if (!draft) throw new Error('预问诊草稿不存在')
        const summary = draft.summary
        const fields = SUMMARY_FIELDS.map((key) => ({
          key,
          label: SUMMARY_FIELD_LABELS[key],
          value: (summary && summary[key]) || '',
        }))
        // 确认前置条件：有摘要快照且建议科室已收敛（未收敛用契约文案提示，禁用确认）
        const hasSummary = !!summary
        const hasDepartment = !!(summary && summary.suggested_standard_department_id)
        this.setData({
          loading: false,
          draftStatusLabel: DRAFT_STATUS_LABELS[draft.status] || '',
          fields,
          departmentName: (summary && summary.suggested_standard_department_name) || '',
          disclaimer: (summary && summary.disclaimer) || '',
          canConfirm: hasSummary && hasDepartment,
          confirmHint: !hasSummary ? TEXTS.summary_required : hasDepartment ? '' : TEXTS.department_unresolved,
        })
      })
      .catch((err) => {
        this.setData({ loading: false })
        my.showToast({ content: (err && err.detail) || '摘要加载失败', type: 'fail' })
      })
  },

  backToChat() {
    my.navigateBack()
  },

  confirm() {
    if (!this.data.canConfirm || this.data.submitting) return
    this.setData({ submitting: true })
    createConsultation(this.data.draftId)
      .then((res) => {
        const consultation = res && res.consultation
        if (!consultation) throw new Error('问诊单创建失败')
        my.redirectTo({ url: `/pages/consult/waiting/index?id=${consultation.id}` })
      })
      .catch((err) => {
        my.showToast({ content: (err && err.detail) || '提交失败，请稍后重试', type: 'fail' })
        this.setData({ submitting: false })
      })
  },
})
