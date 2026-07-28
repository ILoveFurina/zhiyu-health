Component({
  props: {
    kind: '',
    card: {},
    onSelectDoctor: () => {},
    onSelectSlot: () => {},
  },

  methods: {
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
