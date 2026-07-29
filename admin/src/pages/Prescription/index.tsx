import { useCallback, useEffect, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { App, Button, Input, Modal, Space, Table, Tag, type TableColumnsType } from 'antd';
import { prescriptionDecisions } from '@/contracts/prescription';
import { fetchPendingPrescriptions, reviewPrescription, type Prescription, type ReviewDecision } from '@/services/prescription';

export default function PrescriptionPage() {
  const { message } = App.useApp();
  const [rows, setRows] = useState<Prescription[]>([]);
  const [reason, setReason] = useState('');
  const [rejecting, setRejecting] = useState<Prescription>();
  const load = useCallback(() => fetchPendingPrescriptions().then(setRows), []);
  useEffect(() => { load().catch(() => {}); }, [load]);

  const review = async (row: Prescription, decision: ReviewDecision, rejectReason?: string) => {
    await reviewPrescription(row.id, decision, rejectReason);
    message.success(decision === prescriptionDecisions.approve ? '审核已通过，患者现可查看' : '已驳回电子处方');
    setRejecting(undefined); setReason(''); await load();
  };

  const columns: TableColumnsType<Prescription> = [
    { title: '电子处方', dataIndex: 'id', width: 100, render: (v) => `#${v}` },
    { title: '挂号单', dataIndex: 'appointment_id', width: 100, render: (v) => `#${v}` },
    { title: '患者', dataIndex: 'patient_nickname', width: 100 },
    { title: '医生', dataIndex: 'doctor_name', width: 100 },
    { title: '药品', render: (_, row) => row.items.map((item) => `${item.name} ${item.dosage} ${item.frequency} ${item.duration}`).join('；') },
    { title: '状态', dataIndex: 'status', width: 100, render: (v) => <Tag color="gold">{v}</Tag> },
    { title: '操作', width: 160, render: (_, row) => <Space>
      <Button type="link" onClick={() => review(row, prescriptionDecisions.approve)}>通过</Button>
      <Button type="link" danger onClick={() => setRejecting(row)}>驳回</Button>
    </Space> },
  ];
  return <PageContainer title="电子处方审核">
    <Table rowKey="id" columns={columns} dataSource={rows} locale={{ emptyText: '暂无待审核电子处方' }} />
    <Modal title="驳回电子处方" open={!!rejecting} okButtonProps={{ danger: true, disabled: !reason.trim() }}
      onCancel={() => setRejecting(undefined)} onOk={() => rejecting && review(rejecting, prescriptionDecisions.reject, reason)}>
      <Input.TextArea value={reason} onChange={(e) => setReason(e.target.value)} placeholder="填写驳回原因" rows={4} />
    </Modal>
  </PageContainer>;
}
