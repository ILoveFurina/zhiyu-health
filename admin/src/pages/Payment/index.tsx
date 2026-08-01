import { useCallback, useEffect, useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { App, Button, Descriptions, Drawer, Select, Space, Table, Tag, type TableColumnsType } from 'antd';
import { paymentMessages, paymentStatusLabels, paymentStatuses } from '@/contracts/payment';
import {
  getPayment,
  listPayments,
  payPayment,
  type Payment,
  type PaymentStatus,
} from '@/services/payment';

const statusColors = {
  [paymentStatuses.unpaid]: 'gold',
  [paymentStatuses.paid]: 'green',
} as Record<PaymentStatus, string>;

export default function PaymentPage() {
  const { message, modal } = App.useApp();
  const [status, setStatus] = useState<PaymentStatus>();
  const [rows, setRows] = useState<Payment[]>([]);
  const [detail, setDetail] = useState<Payment>();
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await listPayments(status));
    } finally {
      setLoading(false);
    }
  }, [status]);

  useEffect(() => { load().catch(() => {}); }, [load]);

  const openDetail = async (id: number) => setDetail(await getPayment(id));
  const pay = async (row: Payment) => {
    await payPayment(row.id);
    message.success(paymentMessages.pay_success);
    setDetail(undefined);
    await load();
  };

  const columns = useMemo<TableColumnsType<Payment>>(() => [
    { title: '收费记录', dataIndex: 'id', width: 110, render: (value) => `#${value}` },
    { title: '挂号单', dataIndex: 'appointment_id', width: 110, render: (value) => `#${value}` },
    { title: '金额（元）', dataIndex: 'amount', width: 120, render: (value) => Number(value).toFixed(2) },
    { title: '创建时间', dataIndex: 'created_at', width: 220, render: (value) => value || '-' },
    { title: '支付时间', dataIndex: 'paid_at', width: 220, render: (value) => value || '-' },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: PaymentStatus, row) => <Tag color={statusColors[value]}>{row.status_label}</Tag> },
    {
      title: '操作', width: 220, render: (_, row) => <Space>
        <Button type="link" onClick={() => openDetail(row.id)}>查看明细</Button>
        {row.status === paymentStatuses.unpaid && <Button type="link" onClick={() => modal.confirm({
          title: '模拟支付挂号收费', content: `确认将挂号单 #${row.appointment_id} 的收费置为已支付？`, okText: '确认支付',
          onOk: () => pay(row),
        })}>模拟支付</Button>}
      </Space>,
    },
  ], [modal, load]);

  return <PageContainer title="收费管理" extra={[
    <Select key="status" allowClear placeholder="全部状态" value={status} style={{ width: 160 }}
      onChange={(value) => setStatus(value)} options={Object.entries(paymentStatusLabels).map(([value, label]) => ({ value, label }))} />,
  ]}>
    <Table rowKey="id" loading={loading} columns={columns} dataSource={rows} pagination={{ pageSize: 10 }}
      locale={{ emptyText: '暂无挂号收费记录' }} />
    <Drawer title={detail ? `收费记录 #${detail.id}` : '收费明细'} width={520} open={!!detail}
      onClose={() => setDetail(undefined)}>
      {detail && <Descriptions column={1} bordered size="small" items={[
        { key: 'appointment', label: '挂号单', children: `#${detail.appointment_id}` },
        { key: 'amount', label: '诊查费', children: `¥${Number(detail.amount).toFixed(2)}` },
        { key: 'status', label: '状态', children: <Tag color={statusColors[detail.status]}>{detail.status_label}</Tag> },
        { key: 'created', label: '创建时间', children: detail.created_at || '-' },
        { key: 'paid', label: '支付时间', children: detail.paid_at || '-' },
      ]} />}
    </Drawer>
  </PageContainer>;
}
