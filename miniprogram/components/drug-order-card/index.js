/**
 * 购药结果卡（票 77）：展示已建单订单视图（订单号/状态/总价/药品明细/支付·取消入口）。
 *
 * 来源两条路径：① 确认卡「确认下单」调 POST /api/c/drug-orders 成功后由 server-java 落库并
 * 返回 OrderView，C 端追加为 drug_order 卡片；② 历史会话回看时由 messages 表回放还原。
 *
 * content JSON 字段（与 DrugOrderService.OrderView 对齐）：
 *   id(订单号)/status(枚举)/status_label(中文)/total_amount/source(PRESCRIPTION|OTC 枚举值)/
 *   prescription_id/items[{medication_id,name,specification,quantity,unit_price,subtotal}]/
 *   cancellable/payable/created_at
 *
 * 支付/取消复用 services/drug-orders.js（与 drug-orders 列表页同源），操作后由宿主刷新卡片状态。
 */
Component({
  props: {
    card: {}, // OrderView
    cardId: null, // 宿主消息 id，回调原样带回供宿主定位
    // 操作中标记（按订单 id 区分，与列表页 payingId/cancellingId 同形）：宿主控制，禁用按钮
    payingOrderId: null,
    cancellingOrderId: null,
    onPay: () => {},
    onCancel: () => {},
  },

  methods: {
    pay() {
      const order = this.props.card || {}
      if (this.props.payingOrderId === order.id || this.props.cancellingOrderId === order.id) return
      this.props.onPay({ cardId: this.props.cardId, orderId: order.id })
    },
    cancel() {
      const order = this.props.card || {}
      if (this.props.payingOrderId === order.id || this.props.cancellingOrderId === order.id) return
      this.props.onCancel({ cardId: this.props.cardId, orderId: order.id })
    },
  },
})
