import { useCallback, useEffect, useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { App, Button, Card, Descriptions, Drawer, Select, Space, Table, Tag, Tooltip, type TableColumnsType } from 'antd';
import { orderStatusLabels, orderStatuses } from '@/contracts/order';
import {
  cancelDrugOrder,
  getDrugOrder,
  listDrugOrders,
  type DrugOrder,
  type DrugOrderStatus,
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

export default function DrugOrderPage() {
  const { message, modal } = App.useApp();
  const [status, setStatus] = useState<DrugOrderStatus>();
  const [rows, setRows] = useState<DrugOrder[]>([]);
  const [detail, setDetail] = useState<DrugOrder>();
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await listDrugOrders(status));
    } finally {
      setLoading(false);
    }
  }, [status]);

  useEffect(() => { load().catch(() => {}); }, [load]);

  const openDetail = async (id: number) => setDetail(await getDrugOrder(id));
  const cancel = async (row: DrugOrder) => {
    await cancelDrugOrder(row.id);
    message.success('订单已取消，库存已回补');
    setDetail(undefined);
    await load();
  };

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
      title: '电子处方', dataIndex: 'prescription_id', width: 110,
      render: (value) => (value ? `#${value}` : <Tag>非处方药</Tag>),
    },
    { title: '金额（元）', dataIndex: 'total_amount', width: 120, render: (value) => Number(value).toFixed(2) },
    {
      title: '创建时间', dataIndex: 'created_at', width: 160,
      render: (value: string) => value
        ? <Tooltip title={formatDateTime(value)}>{formatRelativeTime(value)}</Tooltip>
        : '-',
    },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: string, row) => <Tag color={statusColors[value]}>{row.status_label}</Tag> },
    {
      title: '操作', width: 250, render: (_, row) => <Space>
        <Button type="link" onClick={() => openDetail(row.id)}>查看明细</Button>
        {row.status === orderStatuses.unpaid && <Button type="link" danger onClick={() => modal.confirm({
          title: '取消药品订单', content: '取消后将回补本单预扣库存。', okText: '确认取消', okButtonProps: { danger: true },
          onOk: () => cancel(row),
        })}>取消</Button>}
        {/* TODO(票88阶段二)：履约推进操作（调剂/发货/送达/待取/取药）随 B 端履约矩阵实现 */}
      </Space>,
    },
  ], [modal, load]);

  const countBy = (s: string) => rows.filter((r) => r.status === s).length;
  const stats = [
    { label: '订单总数', value: rows.length, suffix: '单' },
    { label: '待支付', value: countBy(orderStatuses.unpaid), suffix: '单' },
    { label: '已支付', value: countBy(orderStatuses.paid), suffix: '单' },
    { label: '履约中', value: countBy(orderStatuses.dispensing) + countBy(orderStatuses.shipped) + countBy(orderStatuses.ready_for_pickup), suffix: '单' },
  ];

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="药品订单管理"
        description="管理患者药品订单的状态流转，取消订单将回补预扣库存"
        tags={['状态流转', '库存回补']}
      />
      <StatCards items={stats} />
      <Card title="药品订单列表" extra={
        <Select allowClear placeholder="全部状态" value={status} style={{ width: 160 }}
          onChange={(value) => setStatus(value)} options={Object.entries(orderStatusLabels).map(([value, label]) => ({ value, label }))} />
      }>
        <Table rowKey="id" loading={loading} columns={columns} dataSource={rows} pagination={{ pageSize: 10 }}
          locale={{ emptyText: '暂无药品订单' }} />
      </Card>
      <Drawer title={detail ? `药品订单 #${detail.id}` : '药品订单明细'} width={620} open={!!detail}
        onClose={() => setDetail(undefined)}>
        {detail && <>
          <Descriptions column={2} bordered size="small" items={[
            { key: 'patient', label: '患者', children: detail.patient_name || `#${detail.patient_id}` },
            { key: 'prescription', label: '电子处方', children: detail.prescription_id ? `#${detail.prescription_id}` : '非处方药' },
            { key: 'status', label: '状态', children: <Tag color={statusColors[detail.status]}>{detail.status_label}</Tag> },
            { key: 'amount', label: '订单金额', children: `¥${Number(detail.total_amount).toFixed(2)}` },
            { key: 'createdAt', label: '创建时间', children: formatDateTime(detail.created_at) },
          ]} />
          <Table style={{ marginTop: 20 }} rowKey={(item) => `${item.medication_id}-${item.name}`}
            pagination={false} dataSource={detail.items} columns={[
              { title: '药品', dataIndex: 'name' },
              { title: '规格', dataIndex: 'specification' },
              { title: '单价', dataIndex: 'unit_price', render: (value) => `¥${Number(value).toFixed(2)}` },
              { title: '数量', dataIndex: 'quantity' },
              { title: '小计', dataIndex: 'subtotal', render: (value) => `¥${Number(value).toFixed(2)}` },
            ]} />
        </>}
      </Drawer>
    </PageContainer>
  );
}
