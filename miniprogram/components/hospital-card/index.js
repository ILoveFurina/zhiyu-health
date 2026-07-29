Component({
  props: {
    kind: '',
    card: {},
    onSelectHospital: () => {},
  },

  methods: {
    selectHospital(e) {
      this.props.onSelectHospital({
        hospitalId: e.currentTarget.dataset.id,
        name: e.currentTarget.dataset.name,
        address: e.currentTarget.dataset.address,
      })
    },
  },
})
