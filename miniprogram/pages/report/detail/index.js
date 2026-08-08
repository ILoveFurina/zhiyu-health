const { ensureLogin } = require('../../../utils/auth')
const { getDetail } = require('../../../services/report-interpretations')
const healthObservations = require('../../../services/health-observations')

// 报告解读记录状态（server-java ReportInterpretation.status）→ 徽标
const STATUS_LABELS = {
  SUCCEEDED: '已完成',
  PROCESSING: '解读中',
  FAILED: '解读失败',
}
const PRIORITY_LABELS = {
  red: '尽快咨询（非急救）',
  yellow: '重点关注',
  blue: '日常观察',
  green: '范围内',
}
// 血型类分类值纠错走 picker，不走自由输入
const BLOOD_TYPE_OPTIONS = ['A', 'B', 'AB', 'O']
const RH_OPTIONS = ['阳性', '阴性']

function decorate(detail) {
  const result = detail.result || {}
  const items = (detail.items || []).map((item) => {
    // item_state 来自契约，label 直接用后端中文；这里只推导可操作的按钮集合
    const canConfirm = item.item_state === 'DEPOSITED_UNVERIFIED'
    const canCorrect = canConfirm || item.item_state === 'DEPOSITED_CONFIRMED'
    return {
      ...item,
      priorityLabel: PRIORITY_LABELS[item.priority] || PRIORITY_LABELS.green,
      priorityClass: PRIORITY_LABELS[item.priority] ? item.priority : 'green',
      canConfirm,
      canCorrect,
      hasOps: (canConfirm || canCorrect) && (item.observation_ids || []).length > 0,
      obsList: (item.observation_ids || []).map((id) => ({ id })),
    }
  })
  return {
    ...detail,
    statusLabel: STATUS_LABELS[detail.status] || detail.status,
    statusClass: (detail.status || '').toLowerCase(),
    dateLabel: detail.report_date || detail.sample_or_exam_date || (detail.created_at || '').slice(0, 10),
    summary: result.summary || '',
    actions: result.actions || [],
    unreadable: result.unreadable || [],
    items,
  }
}

/** 报告解读详情独立页（票 61）：逐项展示 AI 提取项沉淀/核验状态，支持确认/纠错/排除。 */
Page({
  data: {
    loading: true,
    detail: null,
    correctDialog: null, // { obsId, name, unit, mode: 'input'|'picker', options, value, pickerIndex }
  },

  onLoad(options) {
    this.id = options && options.id
    this.load()
  },

  load() {
    return ensureLogin()
      .then(() => getDetail(this.id))
      .then((detail) => this.setData({ detail: decorate(detail), loading: false }))
      .catch((error) => {
        console.error('report detail load failed', error)
        this.setData({ loading: false })
        my.showToast({ content: '加载失败，请稍后重试', type: 'fail' })
      })
  },

  reloadWithToast(content) {
    return this.load().then(() => my.showToast({ content, type: 'success' }))
  },

  confirmObservation(e) {
    const id = e.currentTarget.dataset.id
    my.confirm({
      title: '确认指标值',
      content: '确认该指标提取无误，将标记为已确认。',
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      success: (res) => {
        if (!res.confirm) return
        healthObservations
          .confirm(id)
          .then(() => this.reloadWithToast('已确认'))
          .catch(() => my.showToast({ content: '操作失败，请稍后重试', type: 'fail' }))
      },
    })
  },

  rejectObservation(e) {
    const id = e.currentTarget.dataset.id
    my.confirm({
      title: '排除指标',
      content: '确认从健康档案中排除该指标？排除后不再计入健康概要。',
      confirmButtonText: '排除',
      cancelButtonText: '取消',
      success: (res) => {
        if (!res.confirm) return
        healthObservations
          .reject(id)
          .then(() => this.reloadWithToast('已排除'))
          .catch(() => my.showToast({ content: '操作失败，请稍后重试', type: 'fail' }))
      },
    })
  },

  /** 纠错：自绘输入弹层（my.prompt 不可用）；血型类值给分类 picker，指标名/单位固定展示不可编辑。 */
  openCorrect(e) {
    const item = this.data.detail.items[Number(e.currentTarget.dataset.itemIndex)]
    if (!item) return
    const currentValue = (item.value || '').trim()
    let mode = 'input'
    let options = []
    if (BLOOD_TYPE_OPTIONS.includes(currentValue)) {
      mode = 'picker'
      options = BLOOD_TYPE_OPTIONS
    } else if (RH_OPTIONS.includes(currentValue)) {
      mode = 'picker'
      options = RH_OPTIONS
    }
    this.setData({
      correctDialog: {
        obsId: e.currentTarget.dataset.id,
        name: item.name,
        value: `${item.value}${item.unit ? ` ${item.unit}` : ''}`,
        unit: item.unit || '',
        mode,
        options,
        input: '',
        pickerIndex: 0,
      },
    })
  },

  onCorrectInput(e) {
    this.setData({ 'correctDialog.input': e.detail.value })
  },

  onCorrectPickerChange(e) {
    this.setData({ 'correctDialog.pickerIndex': Number(e.detail.value) })
  },

  closeCorrect() {
    this.setData({ correctDialog: null })
  },

  submitCorrect() {
    const dialog = this.data.correctDialog
    if (!dialog) return
    const value = dialog.mode === 'picker' ? dialog.options[dialog.pickerIndex] : dialog.input.trim()
    if (!value) {
      my.showToast({ content: '请输入新值', type: 'none' })
      return
    }
    healthObservations
      .correct(dialog.obsId, value)
      .then(() => {
        this.setData({ correctDialog: null })
        this.reloadWithToast('已更正')
      })
      .catch(() => my.showToast({ content: '操作失败，请稍后重试', type: 'fail' }))
  },

  /** 查看原会话：沿用既有机制，经 globalData 交棒 chat 打开该会话。 */
  openConversation() {
    const conversationId = this.data.detail && this.data.detail.conversation_id
    if (!conversationId) return
    getApp().globalData.pendingOpenConversationId = conversationId
    my.switchTab({ url: '/pages/chat/index' })
  },

  noop() {},
})
