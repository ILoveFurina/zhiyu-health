import { useCallback, useEffect, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { App, Button, Card, Descriptions, Input, Modal, Space, Table, Tag, Typography, type TableColumnsType } from 'antd';
import { prescriptionDecisions, sourceTypes } from '@/contracts/prescription';
import { fetchPendingPrescriptions, reviewPrescription, type Prescription, type ReviewDecision } from '@/services/prescription';
import StatCards from '@/components/StatCards';
import PageHead from '@/components/PageHead';

export default function PrescriptionPage() {
  const { message } = App.useApp();
  const [rows, setRows] = useState<Prescription[]>([]);
  const [reason, setReason] = useState('');
  const [rejecting, setRejecting] = useState<Prescription>();
  // 当前正在审核的处方 id：锁定操作列防重复点击，避免并发重复审核触发 409 误报
  const [reviewingId, setReviewingId] = useState<number>();
  const load = useCallback(() => fetchPendingPrescriptions().then(setRows), []);
  useEffect(() => { load().catch(() => {}); }, [load]);

  const review = async (row: Prescription, decision: ReviewDecision, rejectReason?: string) => {
    setReviewingId(row.id);
    try {
      await reviewPrescription(row.id, decision, rejectReason);
      message.success(decision === prescriptionDecisions.approve ? '审核已通过，患者现可查看' : '已驳回电子处方');
      setRejecting(undefined); setReason(''); await load();
    } catch (err: any) {
      // 幂等冲突（该处方已被审核）：另一处已处理或重复点击所致，不视为失败，刷新保持界面一致
      if (err?.response?.status === 409) {
        message.info('该处方已审核，请勿重复操作');
        setRejecting(undefined); setReason(''); await load();
      }
      // 其余错误（如驳回缺原因 400）由全局 errorHandler 统一弹出
    } finally {
      setReviewingId(undefined);
    }
  };

  const columns: TableColumnsType<Prescription> = [
    { title: '电子处方', dataIndex: 'id', width: 100, render: (v) => `#${v}` },
    { title: '来源', width: 130, render: (_, row) => (
      <Space direction="vertical" size={2}>
        <Tag color={row.source_type === sourceTypes.appointment ? 'geekblue' : 'purple'}>{row.source_type_label}</Tag>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {row.source_type === sourceTypes.appointment
            ? `挂号单 #${row.appointment_id}`
            : `问诊单 #${row.online_consultation_id}`}
        </Typography.Text>
      </Space>
    ) },
    { title: '患者', dataIndex: 'patient_nickname', width: 100 },
    { title: '医生', dataIndex: 'doctor_name', width: 100 },
    { title: '状态', dataIndex: 'status', width: 100, render: (v) => <Tag color="gold">{v}</Tag> },
    { title: '操作', width: 160, render: (_, row) => {
      const loading = reviewingId === row.id;
      return <Space>
        <Button type="link" loading={loading} disabled={reviewingId != null}
          onClick={() => review(row, prescriptionDecisions.approve)}>通过</Button>
        <Button type="link" danger loading={loading} disabled={reviewingId != null}
          onClick={() => setRejecting(row)}>驳回</Button>
      </Space>;
    } },
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
        <Table
          rowKey="id"
          columns={columns}
          dataSource={rows}
          locale={{ emptyText: '暂无待审核电子处方' }}
          expandable={{
            expandedRowRender: (row) => (
              <Descriptions column={1} size="small">
                {/* 诊断/医嘱来自接诊记录，接诊未完成时可能为空 */}
                <Descriptions.Item label="诊断">{row.diagnosis || '暂无（接诊记录尚未完成）'}</Descriptions.Item>
                <Descriptions.Item label="医嘱">{row.advice || '暂无'}</Descriptions.Item>
                <Descriptions.Item label="药品明细">
                  {row.items.map((item) => `${item.name} ${item.dosage} ${item.frequency} ${item.duration}`).join('；')}
                </Descriptions.Item>
              </Descriptions>
            ),
          }}
        />
      </Card>
      <Modal title="驳回电子处方" open={!!rejecting} okButtonProps={{ danger: true, disabled: !reason.trim(), loading: reviewingId === rejecting?.id }}
        onCancel={() => setRejecting(undefined)} onOk={() => rejecting && review(rejecting, prescriptionDecisions.reject, reason)}>
        <Input.TextArea value={reason} onChange={(e) => setReason(e.target.value)} placeholder="填写驳回原因" rows={4} />
      </Modal>
    </PageContainer>
  );
}
