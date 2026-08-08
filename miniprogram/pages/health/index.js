const { ensureLogin } = require('../../utils/auth')
const {
  listProfiles,
  createProfile,
  activateProfile,
  listTimeline,
  getOverview,
  listObservations,
  replaceAllergies,
} = require('../../services/health-profiles')

const RELATIONSHIPS = ['本人', '父亲', '母亲', '配偶', '子女', '其他家人']
const GENDERS = ['女', '男', '其他']
const DEFAULT_DISCLAIMER = '仅供参考，不替代医生诊断'

Page({
  data: {
    loading: true,
    profiles: [],
    activeProfile: null,
    timeline: [],
    showForm: false,
    relationships: RELATIONSHIPS,
    genders: GENDERS,
    relationshipIndex: 0,
    genderIndex: 0,
    form: { display_name: '', birth_date: '' },
    draftAllergies: [],
    allergyInput: '',
    existingAllergyInput: '',
    today: new Date().toISOString().slice(0, 10),
    // 健康概要（票 61）：分区容错，overview 失败不阻塞时间线等既有区块
    overviewLoaded: false,
    categorical: [],
    metrics: [],
    recentReports: [],
    metricsDisclaimer: DEFAULT_DISCLAIMER,
    hasReportSummary: false,
    expandedMetric: null, // { code, name, loading, error, items, disclaimer }
  },

  onLoad(options) {
    if (options && options.create === '1') this.setData({ showForm: true })
  },

  onShow() {
    this.loadProfiles()
  },

  loadProfiles() {
    this.setData({ loading: true })
    return ensureLogin()
      .then(() => listProfiles())
      .then((profiles) => {
        profiles = profiles.map((item) => ({ ...item, avatar: item.display_name.slice(0, 1) }))
        const activeProfile = profiles.find((item) => item.active) || null
        this.setData({ profiles, activeProfile, showForm: this.data.showForm || profiles.length === 0 })
        if (!activeProfile) {
          this.resetOverview()
          return []
        }
        this.loadOverview(activeProfile.id)
        return listTimeline(activeProfile.id)
      })
      .then((timeline) => this.setData({ timeline }))
      .catch(() => my.showToast({ content: '健康档案加载失败', type: 'fail' }))
      .then(() => this.setData({ loading: false }))
  },

  resetOverview() {
    this.setData({
      overviewLoaded: false,
      categorical: [],
      metrics: [],
      recentReports: [],
      hasReportSummary: false,
      expandedMetric: null,
    })
  },

  loadOverview(profileId) {
    this.resetOverview()
    getOverview(profileId)
      .then((overview) => {
        const metrics = (overview.metrics || []).map((metric) => ({
          ...metric,
          // 后端保证 trend 仅 ≥2 点时非空；showTrend 置 false 即降级为只显示最新值
          showTrend: Array.isArray(metric.trend) && metric.trend.length >= 2,
        }))
        const recentReports = (overview.recent_reports || []).map((report) => ({
          ...report,
          dateLabel: report.exam_date || report.report_date || (report.created_at || '').slice(0, 10),
        }))
        this.setData(
          {
            overviewLoaded: true,
            categorical: overview.categorical || [],
            metrics,
            recentReports,
            metricsDisclaimer: overview.disclaimer || DEFAULT_DISCLAIMER,
            hasReportSummary: recentReports.some((report) => Boolean(report.summary)),
            expandedMetric: null,
          },
          () => this.drawAllTrends()
        )
      })
      .catch((error) => {
        console.error('health overview load failed', error)
        this.setData({ overviewLoaded: true })
      })
  },

  toggleForm() {
    this.setData({ showForm: !this.data.showForm })
  },

  onFormInput(e) {
    this.setData({ [`form.${e.currentTarget.dataset.field}`]: e.detail.value })
  },

  onBirthDateChange(e) {
    this.setData({ 'form.birth_date': e.detail.value.replaceAll('/', '-') })
  },

  onGenderChange(e) {
    this.setData({ genderIndex: Number(e.detail.value) })
  },

  onRelationshipChange(e) {
    this.setData({ relationshipIndex: Number(e.detail.value) })
  },

  onAllergyInput(e) {
    this.setData({ allergyInput: e.detail.value })
  },

  addDraftAllergy() {
    const value = this.data.allergyInput.trim()
    if (!value || this.data.draftAllergies.includes(value)) return
    this.setData({ draftAllergies: [...this.data.draftAllergies, value], allergyInput: '' })
  },

  removeDraftAllergy(e) {
    const value = e.currentTarget.dataset.value
    this.setData({ draftAllergies: this.data.draftAllergies.filter((item) => item !== value) })
  },

  submitProfile() {
    const form = this.data.form
    if (!form.display_name.trim() || !form.birth_date) {
      my.showToast({ content: '请填写姓名和出生日期', type: 'none' })
      return
    }
    my.showLoading({ content: '正在创建…' })
    createProfile({
      display_name: form.display_name.trim(),
      gender: GENDERS[this.data.genderIndex],
      birth_date: form.birth_date,
      relationship: RELATIONSHIPS[this.data.relationshipIndex],
      allergies: this.data.draftAllergies,
    })
      .then(() => {
        this.setData({
          showForm: false,
          form: { display_name: '', birth_date: '' },
          draftAllergies: [],
          allergyInput: '',
        })
        return this.loadProfiles()
      })
      .then(() => my.showToast({ content: '档案已创建', type: 'success' }))
      .catch(() => my.showToast({ content: '创建失败，请稍后重试', type: 'fail' }))
      .then(() => my.hideLoading())
  },

  switchProfile(e) {
    const profileId = e.currentTarget.dataset.id
    if (this.data.activeProfile && profileId === this.data.activeProfile.id) return
    activateProfile(profileId)
      .then(() => this.loadProfiles())
      .catch(() => my.showToast({ content: '切换失败', type: 'fail' }))
  },

  onExistingAllergyInput(e) {
    this.setData({ existingAllergyInput: e.detail.value })
  },

  addExistingAllergy() {
    const value = this.data.existingAllergyInput.trim()
    const profile = this.data.activeProfile
    if (!profile || !value || profile.allergies.includes(value)) return
    this.saveAllergies([...profile.allergies, value])
  },

  removeExistingAllergy(e) {
    const value = e.currentTarget.dataset.value
    this.saveAllergies(this.data.activeProfile.allergies.filter((item) => item !== value))
  },

  saveAllergies(allergies) {
    const profile = this.data.activeProfile
    replaceAllergies(profile.id, allergies)
      .then((updated) => {
        updated = { ...updated, avatar: updated.display_name.slice(0, 1) }
        this.setData({
          activeProfile: updated,
          existingAllergyInput: '',
          profiles: this.data.profiles.map((item) => (item.id === updated.id ? updated : item)),
        })
      })
      .catch(() => my.showToast({ content: '过敏史更新失败', type: 'fail' }))
  },

  /** 指标卡展开/收起：展开时拉取该指标历次观测（含核验徽标与参考范围）。 */
  toggleMetric(e) {
    const code = e.currentTarget.dataset.code
    const index = Number(e.currentTarget.dataset.index)
    const current = this.data.expandedMetric
    if (current && current.code === code) {
      this.setData({ expandedMetric: null })
      return
    }
    const metric = this.data.metrics[index]
    const name = metric ? metric.name_zh : code
    this.setData({ expandedMetric: { code, name, loading: true, error: false, items: [], disclaimer: '' } })
    listObservations(this.data.activeProfile.id, code)
      .then((res) => {
        if (!this.data.expandedMetric || this.data.expandedMetric.code !== code) return
        const items = (res.observations || []).map((obs) => ({
          ...obs,
          displayValue:
            obs.display_value ||
            (obs.value_numeric != null ? obs.value_numeric : obs.value_category || '') +
              (obs.unit ? ` ${obs.unit}` : ''),
        }))
        this.setData({
          expandedMetric: {
            code,
            name: res.name_zh || name,
            loading: false,
            error: false,
            items,
            disclaimer: res.disclaimer || DEFAULT_DISCLAIMER,
          },
        })
      })
      .catch((error) => {
        console.error('metric observations load failed', error)
        if (!this.data.expandedMetric || this.data.expandedMetric.code !== code) return
        this.setData({ expandedMetric: { code, name, loading: false, error: true, items: [], disclaimer: '' } })
      })
  },

  openReportDetail(e) {
    my.navigateTo({ url: `/pages/report/detail/index?id=${e.currentTarget.dataset.id}` })
  },

  goUploadReport() {
    // 报告上传链路在报告解读入口页（chooseReport + 分段上传，交棒 chat 解读）
    my.navigateTo({ url: '/pages/report/index' })
  },

  // ---- 趋势图：支付宝 canvas 2d 手写极简折线，任何失败降级为只显示最新值 ----

  drawAllTrends() {
    this.data.metrics.forEach((metric, index) => {
      if (metric.showTrend) this.drawTrend(metric, index)
    })
  },

  drawTrend(metric, index) {
    const degrade = (error) => {
      console.error('trend canvas failed', error)
      this.setData({ [`metrics[${index}].showTrend`]: false })
    }
    my.createSelectorQuery()
      .in(this)
      .select(`#trend-${index}`)
      .fields({ node: true, size: true })
      .exec((res) => {
        try {
          const info = res && res[0]
          if (!info || !info.node) throw new Error('canvas node missing')
          const node = info.node
          const width = info.width
          const height = info.height
          const points = metric.trend.map((point) => Number(point.value))
          if (points.length < 2 || points.some((v) => !isFinite(v))) throw new Error('invalid trend points')
          const ctx = node.getContext('2d')
          const dpr = (my.getSystemInfoSync().pixelRatio) || 1
          node.width = width * dpr
          node.height = height * dpr
          ctx.scale(dpr, dpr)
          this.renderTrend(ctx, width, height, points, metric.trend)
        } catch (error) {
          degrade(error)
        }
      })
  },

  /** 折线本体：按 observed_on 顺序等距取点，value 归一化到画布高度，画折线+圆点+首尾日期。 */
  renderTrend(ctx, width, height, values, trend) {
    const padX = 12
    const padTop = 12
    const padBottom = 26
    const plotW = width - padX * 2
    const plotH = height - padTop - padBottom
    let min = Math.min(...values)
    let max = Math.max(...values)
    if (max === min) {
      max += 1
      min -= 1
    }
    const xAt = (i) => padX + (plotW * i) / (values.length - 1)
    const yAt = (v) => padTop + (1 - (v - min) / (max - min)) * plotH

    ctx.strokeStyle = '#00a870'
    ctx.lineWidth = 2
    ctx.lineJoin = 'round'
    ctx.beginPath()
    values.forEach((v, i) => {
      if (i === 0) ctx.moveTo(xAt(i), yAt(v))
      else ctx.lineTo(xAt(i), yAt(v))
    })
    ctx.stroke()

    ctx.fillStyle = '#00a870'
    values.forEach((v, i) => {
      ctx.beginPath()
      ctx.arc(xAt(i), yAt(v), 3, 0, Math.PI * 2)
      ctx.fill()
    })

    ctx.fillStyle = '#9db3af'
    ctx.font = '10px sans-serif'
    const first = trend[0].observed_on || ''
    const last = trend[trend.length - 1].observed_on || ''
    ctx.textAlign = 'left'
    ctx.fillText(first, padX, height - 8)
    if (last !== first) {
      ctx.textAlign = 'right'
      ctx.fillText(last, width - padX, height - 8)
    }
  },
})
