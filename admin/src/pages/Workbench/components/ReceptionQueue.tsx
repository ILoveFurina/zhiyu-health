import { Button, Card, Table, Tag, type TableColumnsType } from 'antd';
import type { ReceptionAppointment } from '@/services/reception';
import { prescriptionStatusLabels } from '@/contracts/prescription';

interface Props {
  appointments: ReceptionAppointment[];
  onOpen: (id: number) => void;
}

// 处方状态 Tag 配色：待审核金 / 已通过绿 / 已驳回红（与处方审核页一致）
const PRESCRIPTION_COLORS: Record<string, string> = {
  PENDING: 'gold',
  APPROVED: 'green',
  REJECTED: 'red',
};

export default function ReceptionQueue({ appointments, onOpen }: Props) {
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
        return <Tag color={row.status === '已接诊' ? 'green' : 'blue'}>{row.status}</Tag>;
      },
    },
    {
      title: '操作', width: 110,
      render: (_, row) => {
        // 已开方（有处方）不再提供"接诊"入口，避免对审核中医嘱重复接诊
        const viewOnly = row.prescription_status != null || row.status === '已接诊';
        return <Button type="link" onClick={() => onOpen(row.id)}>{viewOnly ? '查看' : '接诊'}</Button>;
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
