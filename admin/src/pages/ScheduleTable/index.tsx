import { useCallback, useEffect, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { App, Button, Card, InputNumber, Modal, Popconfirm, Space, Table, Tag, Typography, type TableColumnsType } from 'antd';
import dayjs from 'dayjs';
import { useModel } from '@umijs/max';
import {
  scheduleRequestMaxTotalSlots,
  scheduleRequestTimeSlotWindows,
} from '@/contracts/scheduleRequest';
import {
  fetchMyScheduleTable,
  submitScheduleChange,
  type Schedule,
} from '@/services/scheduleRequest';
import PageHead from '@/components/PageHead';

export default function ScheduleTablePage() {
  const { message } = App.useApp();
  const [rows, setRows] = useState<Schedule[]>([]);
  const [loading, setLoading] = useState(false);
  // 调整号源 Modal
  const [modifying, setModifying] = useState<Schedule>();
  const [newTotalSlots, setNewTotalSlots] = useState<number>(10);
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await fetchMyScheduleTable();
      setRows(data);
    } catch {
      // 错误由全局 errorHandler 弹出
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load().catch(() => {});
  }, [load]);

  const onAdjustClick = (row: Schedule) => {
    setModifying(row);
    setNewTotalSlots(row.total_slots);
  };

  const onAdjustSubmit = async () => {
    if (!modifying) return;
    if (newTotalSlots < 1) {
      // InputNumber 的 min 只约束步进按钮，不拦键盘输入；手动输 0/负数时先于请求拦截。
      message.warning('号源数必须至少为 1');
      return;
    }
    if (newTotalSlots > scheduleRequestMaxTotalSlots) {
      // 与 min 同理：手动输入超过 max 的越界值不会触发 onChange，这里在提交时拦截。
      message.warning(`号源数最多为 ${scheduleRequestMaxTotalSlots}`);
      return;
    }
    const usedSlots = modifying.total_slots - modifying.remaining_slots;
    if (newTotalSlots < usedSlots) {
      message.warning(`号源数不能小于已约数量（${usedSlots}）`);
      return;
    }
    setSubmitting(true);
    try {
      await submitScheduleChange(modifying.id, 'modify', newTotalSlots);
      message.success('已提交号源调整申请，等待管理员审核');
      setModifying(undefined);
      await load();
    } catch {
      // 错误由全局 errorHandler 弹出
    } finally {
      setSubmitting(false);
    }
  };

  const onDisable = async (row: Schedule) => {
    setSubmitting(true);
    try {
      await submitScheduleChange(row.id, 'disable');
      message.success('已提交停诊申请，等待管理员审核');
      await load();
    } catch {
      // 错误由全局 errorHandler 弹出
    } finally {
      setSubmitting(false);
    }
  };

  const onEnable = async (row: Schedule) => {
    setSubmitting(true);
    try {
      await submitScheduleChange(row.id, 'enable');
      message.success('已提交恢复出诊申请，等待管理员审核');
      await load();
    } catch {
      // 错误由全局 errorHandler 弹出
    } finally {
      setSubmitting(false);
    }
  };

  // 判断排班时段是否已过：当天且当前时间超过时段结束时间（与 server-java isSlotWindowClosed 一致）
  const isSlotExpired = (row: Schedule) => {
    const window = scheduleRequestTimeSlotWindows[row.time_slot as keyof typeof scheduleRequestTimeSlotWindows];
    if (!window) return false;
    const d = dayjs(row.schedule_date);
    if (!d.isSame(dayjs(), 'day')) return false;
    return dayjs().isAfter(dayjs().hour(Number(window.end.split(':')[0])).minute(Number(window.end.split(':')[1])).second(0));
  };

  const columns: TableColumnsType<Schedule> = [
    {
      title: '日期',
      dataIndex: 'schedule_date',
      width: 130,
      sorter: (a, b) => a.schedule_date.localeCompare(b.schedule_date),
      render: (v: string) => {
        const d = dayjs(v);
        const isToday = d.isSame(dayjs(), 'day');
        return (
          <Space direction="vertical" size={0}>
            <Typography.Text strong>{d.format('MM-DD')}</Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {isToday ? '今天' : d.format('dddd')}
            </Typography.Text>
          </Space>
        );
      },
    },
    {
      title: '时段',
      dataIndex: 'time_slot',
      width: 120,
      render: (v: string) => {
        const window = scheduleRequestTimeSlotWindows[v as keyof typeof scheduleRequestTimeSlotWindows];
        return (
          <Space direction="vertical" size={0}>
            <Typography.Text>{v}</Typography.Text>
            {window && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {window.start}-{window.end}
              </Typography.Text>
            )}
          </Space>
        );
      },
    },
    {
      title: '号源',
      width: 120,
      render: (_, row) => (
        <Space direction="vertical" size={0}>
          <Typography.Text>
            已约 <Typography.Text strong>{row.total_slots - row.remaining_slots}</Typography.Text> / {row.total_slots}
          </Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            剩余 {row.remaining_slots} 个
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '状态',
      width: 110,
      render: (_, row) => {
        if (row.pending_action === 'MODIFY') {
          return <Tag color="gold">待审核（调整号源）</Tag>;
        }
        if (row.pending_action === 'DISABLE') {
          return <Tag color="gold">待审核（停诊）</Tag>;
        }
        if (row.pending_action === 'ENABLE') {
          return <Tag color="gold">待审核（接诊）</Tag>;
        }
        if (!row.is_active) {
          return <Tag color="default">已停诊</Tag>;
        }
        if (isSlotExpired(row)) {
          return <Tag color="default">停诊</Tag>;
        }
        return <Tag color="green">可出诊</Tag>;
      },
    },
    {
      title: '操作',
      width: 200,
      render: (_, row) => {
        if (row.pending_action) {
          return <Typography.Text type="secondary">审核中</Typography.Text>;
        }
        if (!row.is_active) {
          return (
            <Popconfirm
              title="确认恢复出诊？"
              description="恢复出诊申请需管理员审核通过后生效"
              onConfirm={() => onEnable(row)}
              okText="确认"
              cancelText="取消"
            >
              <Button type="link" size="small">
                接诊
              </Button>
            </Popconfirm>
          );
        }
        if (isSlotExpired(row)) {
          return <Typography.Text type="secondary">时段已过</Typography.Text>;
        }
        return (
          <Space>
            <Button type="link" size="small" onClick={() => onAdjustClick(row)}>
              调整号源
            </Button>
            <Popconfirm
              title="确认停诊？"
              description="停诊申请需管理员审核通过后生效"
              onConfirm={() => onDisable(row)}
              okText="确认"
              cancelText="取消"
            >
              <Button type="link" size="small" danger>
                停诊
              </Button>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="排班表"
        description="查看本人近期排班情况，如需调整号源数或停诊，可发起申请经管理员审核后生效"
        tags={['未来两周', '调整号源', '停诊申请']}
      />
      <Card>
        <Table
          rowKey="id"
          columns={columns}
          dataSource={rows}
          loading={loading}
          locale={{ emptyText: '暂无排班记录' }}
          pagination={false}
        />
      </Card>
      <Modal
        title="调整号源数"
        open={!!modifying}
        onCancel={() => setModifying(undefined)}
        onOk={onAdjustSubmit}
        confirmLoading={submitting}
        okText="提交申请"
      >
        {modifying && (
          <div>
            <Typography.Text style={{ display: 'block', marginBottom: 12 }}>
              {modifying.schedule_date} {modifying.time_slot}（当前号源 {modifying.total_slots}，已约 {modifying.total_slots - modifying.remaining_slots}）
            </Typography.Text>
            <Typography.Text strong style={{ display: 'block', marginBottom: 8 }}>新号源数</Typography.Text>
            <InputNumber
              min={0}
              precision={0}
              value={newTotalSlots}
              onChange={(v) => setNewTotalSlots(v ?? 1)}
              style={{ width: '100%' }}
            />
            <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12, marginTop: 8 }}>
              号源数须在 1-{scheduleRequestMaxTotalSlots} 之间且不能小于已约数量（{modifying.total_slots - modifying.remaining_slots}），调整申请需管理员审核通过后生效
            </Typography.Text>
          </div>
        )}
      </Modal>
    </PageContainer>
  );
}
