Component({
  props: {
    kind: '',
    card: {},
    showDisclaimer: true,
    onSelectHospital: () => {},
  },

  // axml 无法调用 toFixed，在此把距离格式化为一位小数字符串，避免长尾小数
  deriveDataFromProps(nextProps) {
    const hospitals = (nextProps.card.hospitals || []).map((hospital) => ({
      ...hospital,
      distance_text:
        hospital.distance_km != null
          ? Number(hospital.distance_km).toFixed(1)
          : '',
    }))
    this.setData({ 'card.hospitals': hospitals })
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
