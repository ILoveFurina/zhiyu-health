import { useCallback, useEffect, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { App, Button, Card, Input, Modal, Space, Table, Tag, type TableColumnsType } from 'antd';
import { prescriptionDecisions } from '@/contracts/prescription';
import { fetchPendingPrescriptions, reviewPrescription, type Prescription, type ReviewDecision } from '@/services/prescription';
import StatCards from '@/components/StatCards';
import PageHead from '@/components/PageHead';

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

  const stats = [
    { label: '待审核', value: rows.length, suffix: '张' },
    { label: '涉及患者', value: new Set(rows.map((r) => r.patient_nickname)).size, suffix: '人' },
    { label: '接诊医生', value: new Set(rows.map((r) => r.doctor_name)).size, suffix: '位' },
  ];

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="电子处方审核"
        description="审核医生开具的电子处方，通过后患者可查看并进入药品订单与收费流程"
        tags={['待审核', '通过 / 驳回']}
      />
      <StatCards items={stats} />
      <Card title="待审核处方">
        <Table rowKey="id" columns={columns} dataSource={rows} locale={{ emptyText: '暂无待审核电子处方' }} />
      </Card>
      <Modal title="驳回电子处方" open={!!rejecting} okButtonProps={{ danger: true, disabled: !reason.trim() }}
        onCancel={() => setRejecting(undefined)} onOk={() => rejecting && review(rejecting, prescriptionDecisions.reject, reason)}>
        <Input.TextArea value={reason} onChange={(e) => setReason(e.target.value)} placeholder="填写驳回原因" rows={4} />
      </Modal>
    </PageContainer>
  );
}
