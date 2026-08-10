/**
 * 购药结果卡（票 88）：下单成功后由 server-java 落聊天的订单摘要（非敏感，不含收货地址）。
 * 只读展示订单号/状态/取药方式/履约药房/金额快照与药品明细；支付、取消与履约进度
 * 统一在药品订单详情页管理（实时拉取，避免卡片快照与后端订单状态不同步）。
 *
 * content JSON 字段（与 contracts/order-flow.json 的 drug_order 结果卡说明对齐）：
 *   order_no 或 id/status(枚举)/status_label(中文)/source(PRESCRIPTION|OTC)/
 *   pickup_method(PICKUP|DELIVERY)/pharmacy_name/hospital_name/campus_name/
 *   medication_amount/delivery_fee/total_amount/
 *   items[{medication_id,name,specification,quantity,unit_price,subtotal}]
 */
Component({
  props: {
    card: {},
    cardId: null, // 宿主消息 id，回调原样带回供宿主定位
    onOpenDetail: () => {},
  },

  data: {
    pickupLabel: '',
  },

  deriveDataFromProps(nextProps) {
    const card = nextProps.card || {}
    // 取药方式标签镜像 contracts/order-flow.json pickup_method_labels
    const pickupLabel =
      card.pickup_method === 'PICKUP'
        ? '院区自取'
        : card.pickup_method === 'DELIVERY'
          ? '配送到家'
          : ''
    this.setData({ pickupLabel })
  },

  methods: {
    openDetail() {
      const card = this.props.card || {}
      this.props.onOpenDetail({ cardId: this.props.cardId, orderId: card.id || card.order_no })
    },
  },
})
