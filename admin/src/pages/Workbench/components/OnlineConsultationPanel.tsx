import { useCallback, useEffect, useState } from 'react';
import { Button, Card, Table, Tabs, Tag, type TableColumnsType } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { consultationStatuses } from '@/contracts/consultation';
import { fetchMine, fetchPool, type ConsultationStatus, type PoolItem } from '@/services/consultation';
import { formatDateTime } from '@/utils/time';
import OnlineConsultationDrawer from './OnlineConsultationDrawer';

type TabKey = 'pool' | 'in_progress' | 'completed';

const renderProfile = (_: unknown, row: PoolItem) =>
  `${row.health_profile.display_name} · ${row.health_profile.gender}`;

export default function OnlineConsultationPanel() {
  const [tab, setTab] = useState<TabKey>('pool');
  const [pool, setPool] = useState<PoolItem[]>([]);
  const [mine, setMine] = useState<PoolItem[]>([]);
  const [poolLoading, setPoolLoading] = useState(false);
  const [mineLoading, setMineLoading] = useState(false);
  const [drawerId, setDrawerId] = useState<number>();
  const [drawerOpen, setDrawerOpen] = useState(false);

  const loadPool = useCallback(async () => {
    const res = await fetchPool();
    setPool(res.consultations);
  }, []);

  const loadMine = useCallback(async (key: TabKey) => {
    const status = (key === 'in_progress'
      ? consultationStatuses.in_progress
      : consultationStatuses.completed) as ConsultationStatus;
    const res = await fetchMine(status);
    setMine(res.consultations);
  }, []);

  // 待接诊池：激活时立即加载并每 10s 轮询，切换标签页即清除
  useEffect(() => {
    if (tab !== 'pool') return;
    setPoolLoading(true);
    loadPool().catch(() => {}).finally(() => setPoolLoading(false));
    const timer = setInterval(() => { loadPool().catch(() => {}); }, 10000);
    return () => clearInterval(timer);
  }, [tab, loadPool]);

  // 进行中/已完成：切换到对应标签页时加载
  useEffect(() => {
    if (tab === 'pool') return;
    setMineLoading(true);
    loadMine(tab).catch(() => {}).finally(() => setMineLoading(false));
  }, [tab, loadMine]);

  const refreshPool = async () => {
    setPoolLoading(true);
    try {
      await loadPool();
    } catch {
      // 错误详情由全局 errorHandler 统一弹出
    } finally {
      setPoolLoading(false);
    }
  };

  const openDrawer = (id: number) => {
    setDrawerId(id);
    setDrawerOpen(true);
  };

  const handleChanged = useCallback(() => {
    if (tab === 'pool') {
      loadPool().catch(() => {});
    } else {
      loadMine(tab).catch(() => {});
    }
  }, [tab, loadPool, loadMine]);

  const poolColumns: TableColumnsType<PoolItem> = [
    { title: '患者', dataIndex: ['patient', 'nickname'] },
    { title: '档案', key: 'profile', render: renderProfile },
    {
      title: '主诉', dataIndex: ['summary', 'chief_complaint'], ellipsis: true,
      render: (value) => value ?? '—',
    },
    { title: '创建时间', dataIndex: 'created_at', width: 180, render: (value) => formatDateTime(value) },
    {
      title: '操作', width: 130,
      render: (_, row) => <Button type="link" onClick={() => openDrawer(row.id)}>查看并接诊</Button>,
    },
  ];

  const inProgressColumns: TableColumnsType<PoolItem> = [
    { title: '患者', dataIndex: ['patient', 'nickname'] },
    { title: '档案', key: 'profile', render: renderProfile },
    {
      title: '接诊方式', dataIndex: 'consult_method_label', width: 110,
      render: (value) => (value ? <Tag color="blue">{value}</Tag> : <Tag>未发起</Tag>),
    },
    { title: '接诊时间', dataIndex: 'accepted_at', width: 180, render: (value) => formatDateTime(value) },
    {
      title: '操作', width: 110,
      render: (_, row) => <Button type="link" onClick={() => openDrawer(row.id)}>继续问诊</Button>,
    },
  ];

  const completedColumns: TableColumnsType<PoolItem> = [
    { title: '患者', dataIndex: ['patient', 'nickname'] },
    { title: '档案', key: 'profile', render: renderProfile },
    {
      title: '接诊方式', dataIndex: 'consult_method_label', width: 110,
      render: (value) => (value ? <Tag>{value}</Tag> : '—'),
    },
    { title: '完成时间', dataIndex: 'completed_at', width: 180, render: (value) => formatDateTime(value) },
    {
      title: '操作', width: 110,
      render: (_, row) => <Button type="link" onClick={() => openDrawer(row.id)}>查看</Button>,
    },
  ];

  const mineTable = (columns: TableColumnsType<PoolItem>, emptyText: string) => (
    <Table rowKey="id" columns={columns} dataSource={mine} loading={mineLoading}
      pagination={false} locale={{ emptyText }} />
  );

  return (
    <>
      <Tabs
        activeKey={tab}
        onChange={(key) => setTab(key as TabKey)}
        items={[
          {
            key: 'pool',
            label: '待接诊池',
            children: (
              <Card
                title="待接诊池"
                extra={<Button icon={<ReloadOutlined />} loading={poolLoading} onClick={refreshPool}>刷新</Button>}
              >
                <Table rowKey="id" columns={poolColumns} dataSource={pool} loading={poolLoading}
                  pagination={false} locale={{ emptyText: '暂无等待接诊的问诊单' }} />
              </Card>
            ),
          },
          { key: 'in_progress', label: '进行中', children: <Card title="进行中">{mineTable(inProgressColumns, '暂无进行中的问诊')}</Card> },
          { key: 'completed', label: '已完成', children: <Card title="已完成">{mineTable(completedColumns, '暂无已完成的问诊')}</Card> },
        ]}
      />
      <OnlineConsultationDrawer
        consultationId={drawerId}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        onChanged={handleChanged}
      />
    </>
  );
}
