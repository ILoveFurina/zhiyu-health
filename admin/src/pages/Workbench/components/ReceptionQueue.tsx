import { Button, Card, Space, Table, Tag, type TableColumnsType } from 'antd';
import type { ReceptionAppointment } from '@/services/reception';
import { prescriptionStatusLabels } from '@/contracts/prescription';
import { appointmentStatuses } from '@/contracts/appointment';

interface Props {
  appointments: ReceptionAppointment[];
  onOpen: (id: number) => void;
  onCall: (id: number) => Promise<void>;
}

// 处方状态 Tag 配色：待审核金 / 已通过绿 / 已驳回红（与处方审核页一致）
const PRESCRIPTION_COLORS: Record<string, string> = {
  PENDING: 'gold',
  APPROVED: 'green',
  REJECTED: 'red',
};

export default function ReceptionQueue({ appointments, onOpen, onCall }: Props) {
  const columns: TableColumnsType<ReceptionAppointment> = [
    { title: '序号', dataIndex: 'sequence_number', width: 80, render: (value) => `${value} 号` },
    { title: '患者', dataIndex: 'patient_nickname' },
    { title: '时段', dataIndex: 'time_slot', width: 100 },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (_, row) => {
        // 有处方时优先展示处方状态（药方已进入审核态），不再显示接诊状态
        if (row.prescription_status) {
          return (
            <Tag color={PRESCRIPTION_COLORS[row.prescription_status] ?? 'default'}>
              {prescriptionStatusLabels[row.prescription_status as keyof typeof prescriptionStatusLabels]
                ?? row.prescription_status}
            </Tag>
          );
        }
        const color = row.status_code === appointmentStatuses.visited
          ? 'green'
          : row.status_code === appointmentStatuses.in_progress ? 'orange' : 'blue';
        return <Tag color={color}>{row.status}</Tag>;
      },
    },
    {
      title: '操作', width: 170,
      render: (_, row) => {
        // 票 86：待就诊只可叫号（非当前时段禁用）；就诊中可接诊（即使已开方也可完成）；
        // 已接诊只读查看。
        if (row.status_code === appointmentStatuses.booked) {
          return (
            <Space size={0}>
              <Button type="link" disabled={!row.callable} onClick={() => onCall(row.id)}>叫号</Button>
            </Space>
          );
        }
        if (row.status_code === appointmentStatuses.in_progress) {
          return <Button type="link" onClick={() => onOpen(row.id)}>接诊</Button>;
        }
        return <Button type="link" onClick={() => onOpen(row.id)}>查看</Button>;
      },
    },
  ];

  return (
    <Card title="今日挂号患者">
      <Table rowKey="id" columns={columns} dataSource={appointments} pagination={false}
        locale={{ emptyText: '今日暂无挂号患者' }} />
    </Card>
  );
}
