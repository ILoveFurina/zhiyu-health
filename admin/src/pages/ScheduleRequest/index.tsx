import { useCallback, useEffect, useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { App, Button, Card, Checkbox, DatePicker, InputNumber, Modal, Space, Table, Tag, Typography, type TableColumnsType } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useModel } from '@umijs/max';
import {
  scheduleRequestActionLabels,
  scheduleRequestMaxDaysAhead,
  scheduleRequestMaxTotalSlots,
  scheduleRequestStatusLabels,
  scheduleRequestTimeSlots,
} from '@/contracts/scheduleRequest';
import {
  fetchMyScheduleRequests,
  submitScheduleRequests,
  type ScheduleRequest,
} from '@/services/scheduleRequest';
import PageHead from '@/components/PageHead';

const { RangePicker } = DatePicker;

// 时段选项：从契约推导（键->中文值），只有上午/下午
const TIME_SLOT_OPTIONS = Object.entries(scheduleRequestTimeSlots).map(([key, label]) => ({
  label,
  value: label,
  key,
}));

const STATUS_COLORS: Record<string, string> = {
  PENDING: 'gold',
  APPROVED: 'green',
  REJECTED: 'red',
};

// 可编辑表格行：日期 × 时段 -> 号源数
interface PreviewRow {
  key: string;
  schedule_date: string;
  time_slot: string;
  total_slots: number;
}

export default function ScheduleRequestPage() {
  const { message } = App.useApp();
  const { initialState } = useModel('@@initialState');
  const doctorId = initialState?.currentUser?.doctor_id ?? 0;
  const [rows, setRows] = useState<ScheduleRequest[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [open, setOpen] = useState(false);
  const [dateRange, setDateRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [selectedSlots, setSelectedSlots] = useState<string[]>([]);
  const [previewRows, setPreviewRows] = useState<PreviewRow[]>([]);

  const load = useCallback(() => fetchMyScheduleRequests().then(setRows), []);
  useEffect(() => {
    load().catch(() => {});
  }, [load]);

  // 日期范围限制：只能选今天起 max_days_ahead 天内
  const disabledDate = (current: Dayjs) => {
    if (!current) return false;
    const today = dayjs().startOf('day');
    const max = today.add(scheduleRequestMaxDaysAhead, 'day');
    return current.isBefore(today) || current.isAfter(max);
  };

  // 当日期范围或时段变化时，重新生成预览表格
  const regeneratePreview = useCallback(() => {
    if (!dateRange || selectedSlots.length === 0) {
      setPreviewRows([]);
      return;
    }
    const [start, end] = dateRange;
    const items: PreviewRow[] = [];
    let cursor = start.startOf('day');
    const endDay = end.startOf('day');
    while (cursor.isBefore(endDay) || cursor.isSame(endDay, 'day')) {
      for (const slot of selectedSlots) {
        items.push({
          key: `${cursor.format('YYYY-MM-DD')}-${slot}`,
          schedule_date: cursor.format('YYYY-MM-DD'),
          time_slot: slot,
          total_slots: 10,
        });
      }
      cursor = cursor.add(1, 'day');
    }
    setPreviewRows(items);
  }, [dateRange, selectedSlots]);

  useEffect(() => {
    regeneratePreview();
  }, [regeneratePreview]);

  const onSlotCountChange = (key: string, value: number | null) => {
    setPreviewRows((prev) =>
      prev.map((r) => (r.key === key ? { ...r, total_slots: value ?? 1 } : r)),
    );
  };

  const onSubmit = async () => {
    if (!dateRange || selectedSlots.length === 0 || previewRows.length === 0) {
      message.warning('请填写完整的排班信息');
      return;
    }
    const items = previewRows.map((r) => ({
      schedule_date: r.schedule_date,
      time_slot: r.time_slot,
      total_slots: r.total_slots,
    }));
    setSubmitting(true);
    try {
      await submitScheduleRequests({ doctor_id: doctorId, items });
      message.success(`已提交 ${items.length} 条排班申请，等待管理员审核`);
      setOpen(false);
      setDateRange(null);
      setSelectedSlots([]);
      setPreviewRows([]);
      await load();
    } catch {
      // 错误由全局 errorHandler 弹出
    } finally {
      setSubmitting(false);
    }
  };

  const previewColumns: TableColumnsType<PreviewRow> = [
    { title: '日期', dataIndex: 'schedule_date', width: 130 },
    { title: '时段', dataIndex: 'time_slot', width: 100 },
    {
      title: '号源数',
      dataIndex: 'total_slots',
      width: 120,
      render: (_, row) => (
        <InputNumber
          min={1}
          max={scheduleRequestMaxTotalSlots}
          precision={0}
          value={row.total_slots}
          onChange={(v) => onSlotCountChange(row.key, v)}
          style={{ width: '100%' }}
        />
      ),
    },
  ];

  const columns: TableColumnsType<ScheduleRequest> = [
    { title: '日期', dataIndex: 'schedule_date', width: 120 },
    { title: '时段', dataIndex: 'time_slot', width: 90 },
    {
      title: '操作类型',
      dataIndex: 'action',
      width: 100,
      render: (v: string) => scheduleRequestActionLabels[v as keyof typeof scheduleRequestActionLabels] ?? v,
    },
    { title: '号源数', dataIndex: 'total_slots', width: 90, render: (v) => `${v} 个` },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (v: string) => (
        <Tag color={STATUS_COLORS[v] ?? 'default'}>
          {scheduleRequestStatusLabels[v as keyof typeof scheduleRequestStatusLabels] ?? v}
        </Tag>
      ),
    },
    {
      title: '审核说明',
      dataIndex: 'review_reason',
      render: (v: string | null) =>
        v ? <Typography.Text type="secondary">{v}</Typography.Text> : <Typography.Text type="secondary">-</Typography.Text>,
    },
    { title: '提交时间', dataIndex: 'created_at', width: 170, render: (v: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-') },
  ];

  const stats = useMemo(
    () => [
      { label: '待审核', value: rows.filter((r) => r.status === 'PENDING').length, suffix: '条' },
      { label: '已通过', value: rows.filter((r) => r.status === 'APPROVED').length, suffix: '条' },
      { label: '已驳回', value: rows.filter((r) => r.status === 'REJECTED').length, suffix: '条' },
    ],
    [rows],
  );

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="排班申请"
        description={`选择日期范围与时段批量提交排班申请，限当天起 ${scheduleRequestMaxDaysAhead} 天内，提交后需管理员审核通过才对患者可见`}
        tags={['批量排班', '管理员审核']}
      />
      <Card style={{ marginBottom: 16 }}>
        <Space style={{ marginBottom: 16 }}>
          <Button type="primary" onClick={() => setOpen(true)}>
            提交排班申请
          </Button>
        </Space>
        <div style={{ display: 'flex', gap: 32 }}>
          {stats.map((s) => (
            <div key={s.label}>
              <Typography.Text type="secondary" style={{ fontSize: 13 }}>{s.label}</Typography.Text>
              <div style={{ fontSize: 24, fontWeight: 600, color: '#123f38' }}>
                {s.value}
                <span style={{ fontSize: 13, fontWeight: 400, color: '#5b7470', marginLeft: 4 }}>{s.suffix}</span>
              </div>
            </div>
          ))}
        </div>
      </Card>
      <Card title="我的排班申请">
        <Table
          rowKey="id"
          columns={columns}
          dataSource={rows}
          locale={{ emptyText: '暂无排班申请记录' }}
          pagination={{ defaultPageSize: 10, pageSizeOptions: [10, 20, 50] }}
        />
      </Card>
      <Modal
        title="提交排班申请"
        open={open}
        onCancel={() => {
          setDateRange(null);
          setSelectedSlots([]);
          setPreviewRows([]);
          setOpen(false);
        }}
        onOk={onSubmit}
        confirmLoading={submitting}
        okText={`提交申请`}
        width={640}
        destroyOnClose
      >
        <div style={{ marginBottom: 16 }}>
          <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>日期范围</Typography.Text>
          <RangePicker
            style={{ width: '100%' }}
            disabledDate={disabledDate}
            placeholder={['开始日期', '结束日期']}
            value={dateRange}
            onChange={(v) => setDateRange(v as [Dayjs, Dayjs] | null)}
          />
        </div>
        <div style={{ marginBottom: 16 }}>
          <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>出诊时段</Typography.Text>
          <Checkbox.Group
            options={TIME_SLOT_OPTIONS}
            value={selectedSlots}
            onChange={(v) => setSelectedSlots(v as string[])}
          />
          <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12, marginTop: 4 }}>
            上午 09:00-11:30，下午 14:00-18:00
          </Typography.Text>
        </div>
        {previewRows.length > 0 && (
          <div>
            <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>
              排班明细（共 {previewRows.length} 条，可逐条调整号源数）
            </Typography.Text>
            <Table
              rowKey="key"
              columns={previewColumns}
              dataSource={previewRows}
              pagination={false}
              size="small"
              scroll={{ y: 240 }}
            />
          </div>
        )}
        <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 12 }}>
          提交后将等待管理员审核通过，通过后患者即可挂号。
        </Typography.Text>
      </Modal>
    </PageContainer>
  );
}
