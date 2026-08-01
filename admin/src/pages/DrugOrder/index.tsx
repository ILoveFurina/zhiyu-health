import { useCallback, useEffect, useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { App, Button, Descriptions, Drawer, Select, Space, Table, Tag, type TableColumnsType } from 'antd';
import { orderStatusLabels, orderStatuses } from '@/contracts/order';
import {
  cancelDrugOrder,
  completeDrugOrder,
  getDrugOrder,
  listDrugOrders,
  type DrugOrder,
  type DrugOrderStatus,
} from '@/services/drugOrder';

const statusColors: Record<string, string> = {
  [orderStatuses.unpaid]: 'gold',
  [orderStatuses.paid]: 'blue',
  [orderStatuses.done]: 'green',
  [orderStatuses.cancelled]: 'default',
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
  const mutate = async (row: DrugOrder, action: 'cancel' | 'complete') => {
    await (action === 'cancel' ? cancelDrugOrder(row.id) : completeDrugOrder(row.id));
    message.success(action === 'cancel' ? '订单已取消，库存已回补' : '订单已确认完成');
    setDetail(undefined);
    await load();
  };

  const columns = useMemo<TableColumnsType<DrugOrder>>(() => [
    { title: '订单', dataIndex: 'id', width: 90, render: (value) => `#${value}` },
    { title: '患者 ID', dataIndex: 'patient_id', width: 100 },
    { title: '电子处方', dataIndex: 'prescription_id', width: 110, render: (value) => `#${value}` },
    { title: '金额（元）', dataIndex: 'total_amount', width: 120, render: (value) => Number(value).toFixed(2) },
    { title: '创建时间', dataIndex: 'created_at', width: 220, render: (value) => value || '-' },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: string, row) => <Tag color={statusColors[value]}>{row.status_label}</Tag> },
    {
      title: '操作', width: 250, render: (_, row) => <Space>
        <Button type="link" onClick={() => openDetail(row.id)}>查看明细</Button>
        {row.status === orderStatuses.unpaid && <Button type="link" danger onClick={() => modal.confirm({
          title: '取消药品订单', content: '取消后将回补本单预扣库存。', okText: '确认取消', okButtonProps: { danger: true },
          onOk: () => mutate(row, 'cancel'),
        })}>取消</Button>}
        {row.status === orderStatuses.paid && <Button type="link" onClick={() => mutate(row, 'complete')}>确认完成</Button>}
      </Space>,
    },
  ], [modal, load]);

  return <PageContainer title="药品订单管理" extra={[
    <Select key="status" allowClear placeholder="全部状态" value={status} style={{ width: 160 }}
      onChange={(value) => setStatus(value)} options={Object.entries(orderStatusLabels).map(([value, label]) => ({ value, label }))} />,
  ]}>
    <Table rowKey="id" loading={loading} columns={columns} dataSource={rows} pagination={{ pageSize: 10 }}
      locale={{ emptyText: '暂无药品订单' }} />
    <Drawer title={detail ? `药品订单 #${detail.id}` : '药品订单明细'} width={620} open={!!detail}
      onClose={() => setDetail(undefined)}>
      {detail && <>
        <Descriptions column={2} bordered size="small" items={[
          { key: 'patient', label: '患者 ID', children: detail.patient_id },
          { key: 'prescription', label: '电子处方', children: `#${detail.prescription_id}` },
          { key: 'status', label: '状态', children: <Tag color={statusColors[detail.status]}>{detail.status_label}</Tag> },
          { key: 'amount', label: '订单金额', children: `¥${Number(detail.total_amount).toFixed(2)}` },
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
  </PageContainer>;
}
