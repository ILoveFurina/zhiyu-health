Component({
  props: {
    kind: '',
    card: {},
    onSelectDoctor: () => {},
    onSelectSlot: () => {},
  },

  data: {
    // 头像加载失败降级文字圆（票 59）：key 为 doctor_id
    failed: {},
  },

  methods: {
    onAvatarError(e) {
      const id = e.currentTarget.dataset.id
      if (id == null) return
      this.setData({ [`failed.${id}`]: true })
    },

    selectDoctor(e) {
      this.props.onSelectDoctor({
        doctorId: e.currentTarget.dataset.id,
        name: e.currentTarget.dataset.name,
      })
    },

    selectSlot(e) {
      this.props.onSelectSlot({
        scheduleId: e.currentTarget.dataset.id,
        scheduleDate: e.currentTarget.dataset.date,
        timeSlot: e.currentTarget.dataset.slot,
      })
    },
  },
})
