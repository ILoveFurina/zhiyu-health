import { useCallback, useEffect, useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  App, Button, Card, Descriptions, Drawer, Select, Space, Table, Tag, Timeline, Tooltip, Typography, type TableColumnsType,
} from 'antd';
import {
  orderDecisions,
  orderStatusLabels,
  orderStatuses,
  pickupMethodLabels,
  pickupMethods,
  paymentTimeoutSeconds,
} from '@/contracts/order';
import {
  advanceFulfillment,
  cancelDrugOrder,
  getDrugOrder,
  listDrugOrders,
  type DrugOrder,
  type DrugOrderStatus,
  type PickupMethod,
} from '@/services/drugOrder';
import StatCards from '@/components/StatCards';
import PageHead from '@/components/PageHead';
import { formatDateTime, formatRelativeTime } from '@/utils/time';

const statusColors: Record<string, string> = {
  [orderStatuses.unpaid]: 'gold',
  [orderStatuses.paid]: 'blue',
  [orderStatuses.dispensing]: 'geekblue',
  [orderStatuses.shipped]: 'processing',
  [orderStatuses.delivered]: 'green',
  [orderStatuses.ready_for_pickup]: 'cyan',
  [orderStatuses.picked_up]: 'green',
  [orderStatuses.cancelled]: 'default',
  [orderStatuses.expired]: 'default',
};

// 合法下一步（票 88 前向状态机）：配送 PAID→DISPENSING→SHIPPED→DELIVERED；
// 自取 PAID→DISPENSING→READY_FOR_PICKUP→PICKED_UP。只按当前状态+取药方式出按钮，不给任意状态下拉框
function nextActions(order: DrugOrder): { decision: string; label: string }[] {
  if (order.status === orderStatuses.paid) return [{ decision: orderDecisions.dispense, label: '开始调剂' }];
  if (order.status === orderStatuses.dispensing) {
    return order.pickup_method === pickupMethods.pickup
      ? [{ decision: orderDecisions.ready, label: '待取药' }]
      : [{ decision: orderDecisions.ship, label: '发货' }];
  }
  if (order.status === orderStatuses.shipped) return [{ decision: orderDecisions.deliver, label: '确认送达' }];
  if (order.status === orderStatuses.ready_for_pickup) return [{ decision: orderDecisions.pickup, label: '确认取药' }];
  return [];
}

export default function DrugOrderPage() {
  const { message, modal } = App.useApp();
  const [status, setStatus] = useState<DrugOrderStatus>();
  const [pickupMethod, setPickupMethod] = useState<PickupMethod>();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [total, setTotal] = useState(0);
  const [rows, setRows] = useState<DrugOrder[]>([]);
  const [detail, setDetail] = useState<DrugOrder>();
  const [loading, setLoading] = useState(false);
  const [advancing, setAdvancing] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listDrugOrders({ status, pickup_method: pickupMethod, page, size });
      setRows(result.records);
      setTotal(result.total);
    } finally {
      setLoading(false);
    }
  }, [status, pickupMethod, page, size]);

  useEffect(() => { load().catch(() => {}); }, [load]);

  const refreshDetail = async (id: number) => setDetail(await getDrugOrder(id));

  const cancel = async (row: DrugOrder) => {
    await cancelDrugOrder(row.id);
    message.success('订单已取消，库存已回补');
    setDetail(undefined);
    await load();
  };

  const advance = async (row: DrugOrder, decision: string, label: string) => {
    setAdvancing(true);
    try {
      await advanceFulfillment(row.id, decision);
      message.success(`${label}成功`);
      if (detail?.id === row.id) await refreshDetail(row.id);
      await load();
    } catch (error: any) {
      // 409：状态已被并发推进/条件更新冲突，弹出后端 message
      const detailMsg = error?.response?.data?.detail;
      modal.error({
        title: `${label}失败`,
        content: typeof detailMsg === 'string' ? detailMsg : '订单状态已变化，请刷新后重试',
      });
      await load();
      if (detail?.id === row.id) await refreshDetail(row.id).catch(() => {});
    } finally {
      setAdvancing(false);
    }
  };

  const actionButtons = (row: DrugOrder) => nextActions(row).map(({ decision, label }) => (
    <Button
      key={decision}
      type="link"
      loading={advancing}
      onClick={() => modal.confirm({
        title: `${label}：订单 #${row.id}`,
        content: '将按履约状态机推进订单，操作会记录履约事件。',
        okText: label,
        onOk: () => advance(row, decision, label),
      })}
    >
      {label}
    </Button>
  ));

  const columns = useMemo<TableColumnsType<DrugOrder>>(() => [
    { title: '订单', dataIndex: 'id', width: 90, render: (value) => `#${value}` },
    {
      title: '患者', dataIndex: 'patient_name', width: 130,
      render: (name, row) => (
        <div>
          <div>{name || `#${row.patient_id}`}</div>
          <div style={{ color: '#bfbfbf', fontSize: 12 }}>ID: {row.patient_id}</div>
        </div>
      ),
    },
    {
      title: '电子处方', dataIndex: 'prescription_id', width: 100,
      render: (value) => (value ? `#${value}` : <Tag>非处方药</Tag>),
    },
    {
      title: '取药方式', dataIndex: 'pickup_method', width: 110,
      render: (value: string, row) => (value
        ? <Tag color={value === pickupMethods.delivery ? 'purple' : 'cyan'}>{row.pickup_method_label ?? pickupMethodLabels[value as PickupMethod] ?? value}</Tag>
        : '-'),
    },
    {
      title: '金额（元）', width: 140,
      render: (_, row) => (
        <Tooltip title={`药品 ¥${Number(row.medication_amount ?? 0).toFixed(2)} + 配送费 ¥${Number(row.delivery_fee ?? 0).toFixed(2)}`}>
          ¥{Number(row.total_amount).toFixed(2)}
        </Tooltip>
      ),
    },
    {
      title: '支付截止', dataIndex: 'payment_deadline', width: 140,
      render: (value: string, row) => (row.status === orderStatuses.unpaid && value
        ? <Tooltip title={formatDateTime(value)}>{formatRelativeTime(value)}</Tooltip>
        : '-'),
    },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: string, row) => <Tag color={statusColors[value]}>{row.status_label}</Tag> },
    {
      title: '操作', width: 260, render: (_, row) => <Space wrap>
        <Button type="link" onClick={() => refreshDetail(row.id)}>查看明细</Button>
        {actionButtons(row)}
        {row.status === orderStatuses.unpaid && <Button type="link" danger onClick={() => modal.confirm({
          title: '取消药品订单', content: '取消后将回补本单预扣库存。', okText: '确认取消', okButtonProps: { danger: true },
          onOk: () => cancel(row),
        })}>取消</Button>}
      </Space>,
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
  ], [modal, load, advancing, detail]);

  const countBy = (s: string) => rows.filter((r) => r.status === s).length;
  const stats = [
    { label: '本页订单', value: rows.length, suffix: '单' },
    { label: '待支付', value: countBy(orderStatuses.unpaid), suffix: '单' },
    { label: '待履约', value: countBy(orderStatuses.paid), suffix: '单' },
    { label: '履约中', value: countBy(orderStatuses.dispensing) + countBy(orderStatuses.shipped) + countBy(orderStatuses.ready_for_pickup), suffix: '单' },
  ];

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="药品订单"
        description={`按取药方式推进模拟履约（调剂/发货/送达/待取/取药），待支付订单 ${Math.round(paymentTimeoutSeconds / 60)} 分钟未支付自动过期`}
        tags={['模拟履约', '状态时间线', '库存回补']}
      />
      <StatCards items={stats} />
      <Card title="药品订单列表" extra={
        <Space>
          <Select allowClear placeholder="全部取药方式" value={pickupMethod} style={{ width: 140 }}
            onChange={(value) => { setPickupMethod(value); setPage(1); }}
            options={Object.entries(pickupMethodLabels).map(([value, label]) => ({ value, label }))} />
          <Select allowClear placeholder="全部状态" value={status} style={{ width: 140 }}
            onChange={(value) => { setStatus(value); setPage(1); }}
            options={Object.entries(orderStatusLabels).map(([value, label]) => ({ value, label }))} />
        </Space>
      }>
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={rows}
          pagination={{
            current: page, pageSize: size, total, showSizeChanger: true,
            onChange: (p, s) => { setPage(p); setSize(s); },
          }}
          locale={{ emptyText: '暂无药品订单' }}
        />
      </Card>
      <Drawer title={detail ? `药品订单 #${detail.id}` : '药品订单明细'} width={680} open={!!detail}
        onClose={() => setDetail(undefined)}
        extra={detail && <Space>{actionButtons(detail)}</Space>}>
        {detail && <>
          <Descriptions column={2} bordered size="small" items={[
            { key: 'patient', label: '患者', children: detail.patient_name || `#${detail.patient_id}` },
            { key: 'prescription', label: '电子处方', children: detail.prescription_id ? `#${detail.prescription_id}` : '非处方药' },
            { key: 'status', label: '状态', children: <Tag color={statusColors[detail.status]}>{detail.status_label}</Tag> },
            {
              key: 'pickup', label: '取药方式',
              children: detail.pickup_method
                ? (detail.pickup_method_label ?? pickupMethodLabels[detail.pickup_method])
                : '-',
            },
            { key: 'medAmount', label: '药品金额', children: detail.medication_amount != null ? `¥${Number(detail.medication_amount).toFixed(2)}` : '-' },
            { key: 'fee', label: '配送费', children: detail.delivery_fee != null ? `¥${Number(detail.delivery_fee).toFixed(2)}` : '-' },
            { key: 'amount', label: '订单总额', children: `¥${Number(detail.total_amount).toFixed(2)}` },
            { key: 'deadline', label: '支付截止', children: detail.payment_deadline ? formatDateTime(detail.payment_deadline) : '-' },
            { key: 'pharmacy', label: '履约药房', children: detail.pharmacy_name || '-' },
            { key: 'campus', label: '院区', children: [detail.hospital_name, detail.campus_name].filter(Boolean).join(' / ') || '-' },
            { key: 'createdAt', label: '创建时间', children: formatDateTime(detail.created_at) },
          ]} />

          {detail.pickup_method === pickupMethods.delivery && (
            <>
              <Typography.Title level={5} style={{ marginTop: 20 }}>收货信息</Typography.Title>
              <Descriptions column={1} bordered size="small" items={[
                { key: 'receiver', label: '收货人', children: detail.receiver_name || '-' },
                { key: 'phone', label: '手机号', children: detail.receiver_phone || '-' },
                { key: 'address', label: '收货地址', children: detail.receiver_address || '-' },
                { key: 'carrier', label: '承运方', children: detail.carrier_name || '-' },
                { key: 'tracking', label: '物流单号', children: detail.tracking_no || '-' },
              ]} />
            </>
          )}
          {detail.pickup_method === pickupMethods.pickup && (
            <>
              <Typography.Title level={5} style={{ marginTop: 20 }}>自取信息</Typography.Title>
              <Descriptions column={1} bordered size="small" items={[
                { key: 'pickupAddress', label: '自取地址', children: detail.pickup_address || '-' },
              ]} />
            </>
          )}

          <Typography.Title level={5} style={{ marginTop: 20 }}>药品明细</Typography.Title>
          <Table rowKey={(item) => `${item.medication_id}-${item.name}`}
            pagination={false} dataSource={detail.items} size="small" columns={[
              { title: '药品', dataIndex: 'name' },
              { title: '规格', dataIndex: 'specification' },
              { title: '单价', dataIndex: 'unit_price', render: (value) => `¥${Number(value).toFixed(2)}` },
              { title: '数量', dataIndex: 'quantity' },
              { title: '小计', dataIndex: 'subtotal', render: (value) => `¥${Number(value).toFixed(2)}` },
            ]} />

          {detail.events && detail.events.length > 0 && (
            <>
              <Typography.Title level={5} style={{ marginTop: 20 }}>状态时间线</Typography.Title>
              <Timeline
                items={detail.events.map((event) => ({
                  color: statusColors[event.status] ?? 'gray',
                  children: (
                    <>
                      <Tag color={statusColors[event.status]}>{event.status_label ?? orderStatusLabels[event.status] ?? event.status}</Tag>
                      <span style={{ marginLeft: 8, color: '#5b7470', fontSize: 13 }}>
                        {formatDateTime(event.occurred_at)}
                        {event.operator ? ` · ${event.operator}` : ''}
                      </span>
                    </>
                  ),
                }))}
              />
            </>
          )}
        </>}
      </Drawer>
    </PageContainer>
  );
}
