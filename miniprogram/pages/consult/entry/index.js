const { ensureLogin } = require('../../../utils/auth')
const { currentProfile } = require('../../../services/health-profiles')
const { listConsultations, startOrResumeDraft } = require('../../../services/consultation')
const { DRAFT_STATUSES, STATUSES, ACTIVE_STATUSES, TEXTS } = require('../../../utils/consultation')

/**
 * 在线问诊入口路由页（票 55）：不承载表单，只做分发——
 * 无当前档案 -> 建档引导卡；有等待中/进行中问诊 -> 对应页面；否则开始/恢复预问诊草稿。
 */
Page({
  data: {
    loading: true,
    needProfile: false,
    profileRequiredText: TEXTS.profile_required,
  },

  onShow() {
    this.route()
  },

  route() {
    this.setData({ loading: true, needProfile: false })
    ensureLogin()
      .then(() => currentProfile())
      .then((result) => {
        const profile = result && result.profile
        if (!profile) {
          this.setData({ loading: false, needProfile: true })
          return null
        }
        return listConsultations().then((data) => {
          // 不同档案可分别发起在线问诊：只接续“当前档案”的进行中/等待中问诊
          const active = ((data && data.consultations) || []).find(
            (item) =>
              ACTIVE_STATUSES.indexOf(item.status) !== -1 &&
              item.health_profile_id === profile.id
          )
          if (active) {
            this.redirectToConsult(active.id, active.status)
            return null
          }
          return startOrResumeDraft().then((res) => {
            const draft = res && res.draft
            if (!draft) throw new Error('预问诊草稿创建失败')
            // 已提交草稿只返回关联问诊单，直接去看问诊单，避免重复确认
            if (draft.status === DRAFT_STATUSES.submitted && draft.current_consultation_id) {
              this.redirectToConsult(draft.current_consultation_id, '')
              return null
            }
            my.redirectTo({
              url:
                `/pages/consult/preconsult/index?draftId=${draft.id}` +
                `&profileName=${encodeURIComponent(profile.display_name)}`,
            })
          })
        })
      })
      .catch((err) => {
        this.setData({ loading: false })
        my.showToast({ content: (err && err.detail) || '加载失败，请稍后重试', type: 'fail' })
      })
  },

  /** 按问诊单状态分流：等待中去等待页，其余（进行中/已完成/终态）去医生问诊页自持分流。 */
  redirectToConsult(id, status) {
    const url =
      status === STATUSES.waiting_doctor || !status
        ? `/pages/consult/waiting/index?id=${id}`
        : `/pages/consult/doctor/index?id=${id}`
    my.redirectTo({ url })
  },

  startHealthProfile() {
    my.navigateTo({ url: '/pages/health/index?create=1' })
  },
})
