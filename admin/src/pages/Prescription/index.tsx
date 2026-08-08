import { useCallback, useEffect, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { App, Button, Card, Descriptions, Input, Modal, Select, Space, Table, Tag, Typography, type TableColumnsType } from 'antd';
import { prescriptionDecisions, prescriptionStatuses, prescriptionStatusLabels, sourceTypes } from '@/contracts/prescription';
import { fetchPrescriptions, reviewPrescription, type Prescription, type PrescriptionItem, type ReviewDecision } from '@/services/prescription';
import StatCards from '@/components/StatCards';
import PageHead from '@/components/PageHead';

const statusColors = {
  [prescriptionStatuses.pending]: 'gold',
  [prescriptionStatuses.approved]: 'green',
  [prescriptionStatuses.rejected]: 'red',
} as Record<string, string>;

// 契约字面量联合类型用 string 索引需放宽为 Record<string, string>
const statusLabels = prescriptionStatusLabels as Record<string, string>;

export default function PrescriptionPage() {
  const { message } = App.useApp();
  const [rows, setRows] = useState<Prescription[]>([]);
  const [status, setStatus] = useState<string>();
  const [keyword, setKeyword] = useState('');
  const [reason, setReason] = useState('');
  const [rejecting, setRejecting] = useState<Prescription>();
  // 当前正在审核的处方 id：仅锁定该行防重复点击，其余行保持可点击；
  // 并发安全由后端 WHERE status=PENDING 条件更新保证，无需全局锁。
  const [reviewingId, setReviewingId] = useState<number>();
  const load = useCallback(
    () => fetchPrescriptions(status, keyword.trim() || undefined).then(setRows),
    [status, keyword],
  );
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

  const pendingLabel = statusLabels[prescriptionStatuses.pending];
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
    { title: '状态', dataIndex: 'status', width: 100, render: (v: string) => {
      // status 由后端下发中文标签，反查状态码取颜色；未知状态兜底 gold
      const code = Object.keys(statusLabels).find((k) => statusLabels[k] === v);
      return <Tag color={(code && statusColors[code]) || 'gold'}>{v}</Tag>;
    } },
    { title: '操作', width: 160, render: (_, row) => {
      // 已审核（非待审核）记录不显示通过/驳回按钮，避免无意义点击触发 409
      if (row.status !== pendingLabel) {
        return <Typography.Text type="secondary">已审核</Typography.Text>;
      }
      const loading = reviewingId === row.id;
      // 不同行之间互不影响：loading 仅锁定正在审核的当前行（antd loading 态按钮自身不可重复点击），
      // 其余待审核行保持可点击；并发安全由后端 WHERE status=PENDING 条件更新保证。
      return <Space>
        <Button type="link" loading={loading} disabled={loading}
          onClick={() => review(row, prescriptionDecisions.approve)}>通过</Button>
        <Button type="link" danger loading={loading} disabled={loading}
          onClick={() => setRejecting(row)}>驳回</Button>
      </Space>;
    } },
  ];

  const stats = [
    { label: '处方总数', value: rows.length, suffix: '张' },
    { label: '待审核', value: rows.filter((r) => r.status === pendingLabel).length, suffix: '张' },
    { label: '涉及患者', value: new Set(rows.map((r) => r.patient_nickname)).size, suffix: '人' },
  ];

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="电子处方审核"
        description="审核医生开具的电子处方，通过后患者可查看并进入药品订单与收费流程；支持按状态、医生、患者、处方号筛选"
        tags={['全部状态', '通过 / 驳回']}
      />
      <StatCards items={stats} />
      <Card title="处方记录" extra={
        <Space>
          <Select allowClear placeholder="全部状态" value={status} style={{ width: 140 }}
            onChange={(value) => setStatus(value)}
            options={Object.entries(prescriptionStatusLabels).map(([value, label]) => ({ value, label }))} />
          <Input.Search allowClear placeholder="医生 / 患者 / 处方号" style={{ width: 220 }}
            onSearch={(v) => setKeyword(v)}
            onClear={() => setKeyword('')} />
        </Space>
      }>
        <Table
          rowKey="id"
          columns={columns}
          dataSource={rows}
          pagination={{ pageSize: 10 }}
          locale={{ emptyText: '暂无电子处方记录' }}
          expandable={{
            expandedRowRender: (row) => (
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                {/* 诊断/医嘱来自接诊记录，接诊未完成时可能为空 */}
                <Descriptions column={2} size="small">
                  <Descriptions.Item label="诊断">{row.diagnosis || '暂无（接诊记录尚未完成）'}</Descriptions.Item>
                  <Descriptions.Item label="医嘱">{row.advice || '暂无'}</Descriptions.Item>
                </Descriptions>
                {/* 药品明细列对齐接诊台开方表单（PrescriptionForm），避免文本拼接丢规格/备注 */}
                <Table<PrescriptionItem> rowKey={(item) => `${item.medication_id}-${item.name}`} size="small"
                  pagination={false} dataSource={row.items}
                  columns={[
                    { title: '药品', dataIndex: 'name', width: 180,
                      render: (v, item) => <Space direction="vertical" size={0}>
                        <span>{v}</span>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{item.specification || '-'}</Typography.Text>
                      </Space> },
                    { title: '单次剂量', dataIndex: 'dosage', width: 100 },
                    { title: '频次', dataIndex: 'frequency', width: 100 },
                    { title: '疗程', dataIndex: 'duration', width: 100 },
                    { title: '备注', dataIndex: 'notes', render: (v) => v || '-' },
                  ]} />
              </Space>
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
