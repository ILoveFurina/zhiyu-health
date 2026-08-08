import { useCallback, useEffect, useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { App, Button, Card, Input, Modal, Space, Table, Tag, Typography, type TableColumnsType } from 'antd';
import dayjs from 'dayjs';
import {
  scheduleRequestActionLabels,
  scheduleRequestDecisions,
  scheduleRequestStatusLabels,
} from '@/contracts/scheduleRequest';
import {
  fetchScheduleRequestsForReview,
  reviewScheduleRequest,
  type ScheduleRequest,
  type ScheduleReviewDecision,
} from '@/services/scheduleRequest';
import StatCards from '@/components/StatCards';
import PageHead from '@/components/PageHead';

const STATUS_COLORS: Record<string, string> = {
  PENDING: 'gold',
  APPROVED: 'green',
  REJECTED: 'red',
};

export default function ScheduleReviewPage() {
  const { message } = App.useApp();
  const [rows, setRows] = useState<ScheduleRequest[]>([]);
  const [reason, setReason] = useState('');
  const [rejecting, setRejecting] = useState<ScheduleRequest>();
  // 当前正在审核的申请 id：锁定操作列防重复点击，避免并发重复审核触发 409 误报
  const [reviewingId, setReviewingId] = useState<number>();

  const load = useCallback(() => fetchScheduleRequestsForReview().then(setRows), []);
  useEffect(() => {
    load().catch(() => {});
  }, [load]);

  const review = async (row: ScheduleRequest, decision: ScheduleReviewDecision, rejectReason?: string) => {
    setReviewingId(row.id);
    try {
      await reviewScheduleRequest(row.id, decision, rejectReason);
      message.success(
        decision === scheduleRequestDecisions.approve
          ? '审核已通过，排班已同步至 C 端，患者可挂号'
          : '已驳回排班申请',
      );
      setRejecting(undefined);
      setReason('');
      await load();
    } catch (err: any) {
      // 幂等冲突（该申请已被审核）：另一处已处理或重复点击所致，不视为失败，刷新保持界面一致
      if (err?.response?.status === 409) {
        message.info('该排班申请已审核，请勿重复操作');
        setRejecting(undefined);
        setReason('');
        await load();
      }
      // 其余错误（如驳回缺原因 400）由全局 errorHandler 统一弹出
    } finally {
      setReviewingId(undefined);
    }
  };

  const columns: TableColumnsType<ScheduleRequest> = [
    { title: '申请编号', dataIndex: 'id', width: 100, render: (v) => `#${v}` },
    { title: '医生', width: 130, render: (_, row) => (
      <Space direction="vertical" size={2}>
        <Typography.Text strong>{row.doctor_name ?? `医生#${row.doctor_id}`}</Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {row.title ? `${row.title} · ` : ''}{row.department_name ?? '-'}
        </Typography.Text>
      </Space>
    ) },
    { title: '出诊日期', dataIndex: 'schedule_date', width: 120 },
    { title: '时段', dataIndex: 'time_slot', width: 90 },
    {
      title: '操作类型',
      dataIndex: 'action',
      width: 100,
      render: (v: string) => (
        <Tag color={v === 'CREATE' ? 'blue' : v === 'MODIFY' ? 'orange' : 'volcano'}>
          {scheduleRequestActionLabels[v as keyof typeof scheduleRequestActionLabels] ?? v}
        </Tag>
      ),
    },
    { title: '号源数', dataIndex: 'total_slots', width: 90, render: (v) => `${v} 个` },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: string) => (
      <Tag color={STATUS_COLORS[v] ?? 'default'}>
        {scheduleRequestStatusLabels[v as keyof typeof scheduleRequestStatusLabels] ?? v}
      </Tag>
    ) },
    { title: '提交时间', dataIndex: 'created_at', width: 160, render: (v: string) =>
      v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-' },
    { title: '操作', width: 160, render: (_, row) => {
      const loading = reviewingId === row.id;
      const isPending = row.status === 'PENDING';
      return (
        <Space>
          {isPending ? (
            <>
              <Button type="link" loading={loading} disabled={reviewingId != null}
                onClick={() => review(row, scheduleRequestDecisions.approve)}>通过</Button>
              <Button type="link" danger loading={loading} disabled={reviewingId != null}
                onClick={() => setRejecting(row)}>驳回</Button>
            </>
          ) : (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {row.review_reason ? `已驳回：${row.review_reason}` : '已通过'}
            </Typography.Text>
          )}
        </Space>
      );
    } },
  ];

  const stats = useMemo(
    () => [
      { label: '待审核', value: rows.filter((r) => r.status === 'PENDING').length, suffix: '条' },
      { label: '涉及医生', value: new Set(rows.map((r) => r.doctor_id)).size, suffix: '位' },
      { label: '涉及科室', value: new Set(rows.map((r) => r.department_name)).size, suffix: '个' },
    ],
    [rows],
  );

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="排班审核"
        description="审核医生提交的排班申请，通过后排班同步至 C 端小程序，患者可挂号；驳回需填写原因"
        tags={['待审核', '通过 / 驳回']}
      />
      <StatCards items={stats} />
      <Card title="待审核排班申请">
        <Table
          rowKey="id"
          columns={columns}
          dataSource={rows}
          locale={{ emptyText: '暂无待审核排班申请' }}
          pagination={{ defaultPageSize: 10, pageSizeOptions: [10, 20, 50] }}
        />
      </Card>
      <Modal
        title="驳回排班申请"
        open={!!rejecting}
        okButtonProps={{ danger: true, disabled: !reason.trim(), loading: reviewingId === rejecting?.id }}
        onCancel={() => setRejecting(undefined)}
        onOk={() => rejecting && review(rejecting, scheduleRequestDecisions.reject, reason)}
      >
        <Input.TextArea
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="填写驳回原因（将展示给医生）"
          rows={4}
        />
      </Modal>
    </PageContainer>
  );
}
