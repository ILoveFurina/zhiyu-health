const { ensureLogin } = require('../../utils/auth')
const {
  listProfiles,
  createProfile,
  activateProfile,
  listTimeline,
  replaceAllergies,
} = require('../../services/health-profiles')

const RELATIONSHIPS = ['本人', '父亲', '母亲', '配偶', '子女', '其他家人']
const GENDERS = ['女', '男', '其他']

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
  },

  onShow() {
    this.loadProfiles()
  },

  loadProfiles() {
    this.setData({ loading: true })
    ensureLogin()
      .then(() => listProfiles())
      .then((profiles) => {
        profiles = profiles.map((item) => ({ ...item, avatar: item.display_name.slice(0, 1) }))
        const activeProfile = profiles.find((item) => item.active) || null
        this.setData({ profiles, activeProfile, showForm: profiles.length === 0 })
        return activeProfile ? listTimeline(activeProfile.id) : []
      })
      .then((timeline) => this.setData({ timeline }))
      .catch(() => my.showToast({ content: '健康档案加载失败', type: 'fail' }))
      .then(() => this.setData({ loading: false }))
  },

  toggleForm() {
    this.setData({ showForm: !this.data.showForm })
  },

  onFormInput(e) {
    this.setData({ [`form.${e.currentTarget.dataset.field}`]: e.detail.value })
  },

  onBirthDateChange(e) {
    this.setData({ 'form.birth_date': e.detail.value })
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
})
