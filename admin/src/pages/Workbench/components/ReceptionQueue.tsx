import { Button, Card, Table, Tag, type TableColumnsType } from 'antd';
import type { ReceptionAppointment } from '@/services/reception';

interface Props {
  appointments: ReceptionAppointment[];
  onOpen: (id: number) => void;
}

export default function ReceptionQueue({ appointments, onOpen }: Props) {
  const columns: TableColumnsType<ReceptionAppointment> = [
    { title: '序号', dataIndex: 'sequence_number', width: 80, render: (value) => `${value} 号` },
    { title: '患者', dataIndex: 'patient_nickname' },
    { title: '时段', dataIndex: 'time_slot', width: 100 },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (value) => <Tag color={value === '已接诊' ? 'green' : 'blue'}>{value}</Tag>,
    },
    {
      title: '操作', width: 110,
      render: (_, row) => <Button type="link" onClick={() => onOpen(row.id)}>{row.status === '已接诊' ? '查看' : '接诊'}</Button>,
    },
  ];

  return (
    <Card title="今日挂号患者">
      <Table rowKey="id" columns={columns} dataSource={appointments} pagination={false}
        locale={{ emptyText: '今日暂无挂号患者' }} />
    </Card>
  );
}
