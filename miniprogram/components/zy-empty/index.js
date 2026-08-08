Component({
  props: {
    // 空态图标名：app.acss 图标字体类名后缀（zy-ico-{icon}）
    icon: 'file',
    text: '暂无数据',
    // 可选 CTA 文案；非空时渲染按钮并支持 onCta 回调
    ctaText: '',
  },
  methods: {
    onCtaTap() {
      if (this.props.onCta) this.props.onCta()
    },
  },
})
