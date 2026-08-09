import { useCallback, useEffect, useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Column } from '@ant-design/charts';
import dayjs, { type Dayjs } from 'dayjs';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Input,
  Popconfirm,
  Radio,
  Row,
  Space,
  Statistic,
  Tag,
  TimePicker,
  Typography,
} from 'antd';
import {
  fetchDashboard,
  fetchKnowledgeSource,
  fetchTimeSlotWindows,
  putTimeSlotWindows,
  putKnowledgeSource,
  resetDemo,
  type DashboardView,
  type KnowledgeSourceView,
  type ResetResult,
  type TimeSlotWindow,
} from '@/services/demo';
import { knowledgeSourceValues as KS_VALUES } from '@/contracts/demoArsenal';
import PageHead from '@/components/PageHead';

const KS_LABELS: Record<string, string> = {
  rag: 'RAG 知识库',
  graph: '知识图谱',
  none: '裸 LLM（关闭）',
};

const STEP_LABELS: Record<string, string> = {
  freeze: '冻结 C 端',
  clear_redis: '清 Redis 演示键',
  truncate_tables: '清 PG 业务表',
  reseed: '重灌幂等 seed',
  rebuild_redis: '重建 Redis 计数',
  unfreeze: '解冻',
  assert: '一致性断言',
};

/**
 * 演示武器包页（票 25）：演示看板 / 知识源现场切换 / 演示重置三件套。
 *
 * 全部收口在 /api/b/demo/**，仅 admin 可用（AdminInterceptor）。看板数据经 server-java
 * 聚合实时读取；知识源切换写 Redis 全局单键，切换后发新对话看效果；重置三重保护在
 * server-java 执行，中途失败保持冻结、返回步骤清单可重跑。
 */
export default function DemoPage() {
  const { message } = App.useApp();
  const [dashboard, setDashboard] = useState<DashboardView>();
  const [ksView, setKsView] = useState<KnowledgeSourceView>({ knowledge_source: 'none' });
  const [ksLoading, setKsLoading] = useState(false);
  const [resetConfirm, setResetConfirm] = useState('');
  const [resetting, setResetting] = useState(false);
  const [resetResult, setResetResult] = useState<ResetResult>();
  const [slotWindows, setSlotWindows] = useState<Record<string, TimeSlotWindow> | null>(null);
  const [slotEnabled, setSlotEnabled] = useState<boolean | null>(null);
  const [slotSaving, setSlotSaving] = useState(false);

  const loadDashboard = useCallback(async () => {
    try {
      setDashboard(await fetchDashboard());
    } catch {
      message.error('看板加载失败');
    }
  }, [message]);

  const loadKs = useCallback(async () => {
    try {
      setKsView(await fetchKnowledgeSource());
    } catch {
      message.error('知识源状态加载失败');
    }
  }, [message]);

  const loadSlotWindows = useCallback(async () => {
    try {
      const view = await fetchTimeSlotWindows();
      setSlotWindows(view.time_slot_windows);
      setSlotEnabled(true);
    } catch (e: any) {
      // env 未开启：整个能力 403 不可用，前端展示未开启态而非报错
      if (e?.response?.status === 403) {
        setSlotEnabled(false);
        setSlotWindows(null);
      } else {
        message.error('时段设置加载失败');
      }
    }
  }, [message]);

  useEffect(() => {
    loadDashboard().catch(() => {});
    loadKs().catch(() => {});
    loadSlotWindows().catch(() => {});
  }, [loadDashboard, loadKs, loadSlotWindows]);

  const switchKs = async (value: string) => {
    setKsLoading(true);
    try {
      setKsView(await putKnowledgeSource(value));
      message.success('知识源已切换，发新对话即可看效果');
    } catch {
      message.error('知识源切换失败');
    } finally {
      setKsLoading(false);
    }
  };

  const doReset = async () => {
    setResetting(true);
    setResetResult(undefined);
    try {
      const result = await resetDemo(resetConfirm);
      setResetResult(result);
      if (result.success) {
        message.success('演示重置完成');
        await loadDashboard();
      } else {
        message.warning('重置中途失败，保持冻结，可从失败步重跑');
      }
    } catch (e: any) {
      const detail = e?.response?.data?.detail;
      message.error(typeof detail === 'string' ? detail : '重置请求失败');
    } finally {
      setResetting(false);
    }
  };

  const changeSlotWindow = (key: string, times: [Dayjs | null, Dayjs | null] | null) => {
    setSlotWindows((prev) => {
      const next = { ...(prev ?? {}) };
      if (times && times[0] && times[1]) {
        next[key] = { start: times[0].format('HH:mm'), end: times[1].format('HH:mm') };
      }
      return next;
    });
  };

  const saveSlotWindows = async () => {
    if (!slotWindows) return;
    setSlotSaving(true);
    try {
      const view = await putTimeSlotWindows(slotWindows);
      setSlotWindows(view.time_slot_windows);
      message.success('时段设置已保存，C 端挂号截止与 B 端叫号即时生效');
    } catch (e: any) {
      const detail = e?.response?.data?.detail;
      message.error(typeof detail === 'string' ? detail : '时段设置保存失败');
    } finally {
      setSlotSaving(false);
    }
  };

  const usagePercent = useMemo(() => {
    const rate = dashboard?.slot_usage?.rate ?? 0;
    return `${(rate * 100).toFixed(1)}%`;
  }, [dashboard]);

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="演示武器包"
        description="演示看板、知识源现场切换与演示重置三件套，仅管理员可用"
        tags={['看板', '知识源切换', '重置保护']}
      />
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        {/* 演示看板 */}
        <Card title="演示看板" extra={<Button onClick={loadDashboard}>刷新</Button>}>
          <Row gutter={16}>
            <Col span={6}>
              <Statistic title="今日挂号量" value={dashboard?.today_appointments ?? '-'} />
            </Col>
            <Col span={6}>
              <Statistic title="号源使用率" value={usagePercent} />
            </Col>
            <Col span={6}>
              <Statistic
                title="Agent 对话量（今日）"
                value={dashboard?.agent_activity?.chat_rounds ?? '-'}
              />
            </Col>
            <Col span={6}>
              <Statistic
                title="工具调用次数（今日）"
                value={dashboard?.agent_activity?.tool_calls ?? '-'}
              />
            </Col>
          </Row>
          <Typography.Title level={5} style={{ marginTop: 24 }}>
            科室分布（今日挂号）
          </Typography.Title>
          <Column
            data={dashboard?.department_distribution ?? []}
            xField="department"
            yField="count"
            height={260}
            label={{ text: 'count' }}
          />
        </Card>

        {/* 知识源现场切换 */}
        <Card title="知识源现场切换" extra={<Button onClick={loadKs}>刷新</Button>}>
          <Typography.Paragraph type="secondary">
            切换后发新对话即可看效果（串行切换，不做并行三路对比）。默认 none 等价关闭，仅对未显式带值
            的 C 端对话请求补位透传。
          </Typography.Paragraph>
          <Radio.Group
            value={ksView.knowledge_source}
            onChange={(e) => switchKs(e.target.value)}
            disabled={ksLoading}
          >
            {KS_VALUES.map((v) => (
              <Radio.Button key={v} value={v}>
                {KS_LABELS[v] ?? v}
              </Radio.Button>
            ))}
          </Radio.Group>
        </Card>

        {/* 时段设置（票 87，ADR-0022 模式） */}
        <Card
          title="时段设置"
          extra={
            slotEnabled === false ? (
              <Tag>未开启</Tag>
            ) : (
              <Button onClick={loadSlotWindows} disabled={!slotEnabled}>刷新</Button>
            )
          }
        >
          {slotEnabled === false ? (
            <Alert
              type="info"
              showIcon
              message="演示时段设置未开启（DEMO_TIME_SLOT_ENABLED=false）"
              description="开启后即可覆盖上午/下午起止，C 端挂号截止与 B 端叫号统一走有效时段窗口。"
            />
          ) : (
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              <Row gutter={16}>
                {['上午', '下午'].map((key) => {
                  const window = slotWindows?.[key];
                  return (
                    <Col key={key} span={12}>
                      <div style={{ marginBottom: 8 }}>{key}</div>
                      <TimePicker.RangePicker
                        format="HH:mm"
                        minuteStep={5}
                        value={window ? [dayjs(window.start, 'HH:mm'), dayjs(window.end, 'HH:mm')] : [null, null]}
                        onChange={(times) => changeSlotWindow(key, times)}
                        disabled={slotSaving || !slotEnabled}
                        style={{ width: '100%' }}
                      />
                    </Col>
                  );
                })}
              </Row>
              <Button type="primary" onClick={saveSlotWindows} loading={slotSaving} disabled={!slotEnabled}>
                保存时段
              </Button>
            </Space>
          )}
        </Card>

        {/* 演示重置 */}
        <Card title="演示重置">
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message="重置会清空全部演示业务数据（挂号/对话/处方等）并重灌 seed，不触碰知识基线"
            description="需环境开关 DEMO_RESET_ENABLED=true、确认短语、进程内互斥锁三重保护同时满足才执行。中途失败保持冻结，可从失败步重跑。"
          />
          <Space>
            <Input
              placeholder="输入确认短语"
              value={resetConfirm}
              onChange={(e) => setResetConfirm(e.target.value)}
              style={{ width: 260 }}
              disabled={resetting}
            />
            <Popconfirm
              title="确认执行演示重置？"
              description="将清空全部演示业务数据并重灌 seed"
              onConfirm={doReset}
              disabled={resetting || !resetConfirm}
            >
              <Button type="primary" danger loading={resetting} disabled={!resetConfirm}>
                执行重置
              </Button>
            </Popconfirm>
          </Space>

          {resetResult && (
            <div style={{ marginTop: 16 }}>
              <Space>
                <Tag color={resetResult.success ? 'green' : 'red'}>
                  {resetResult.success ? '成功' : '中途失败'}
                </Tag>
                {resetResult.frozen_after && <Tag color="orange">已冻结 C 端</Tag>}
              </Space>
              <Row gutter={16} style={{ marginTop: 12 }}>
                <Col span={8}>
                  <Typography.Text strong>已完成步骤</Typography.Text>
                  <ul>
                    {resetResult.completed_steps.map((s) => (
                      <li key={s}>{STEP_LABELS[s] ?? s}</li>
                    ))}
                  </ul>
                </Col>
                {!resetResult.success && (
                  <Col span={8}>
                    <Typography.Text strong>失败步骤</Typography.Text>
                    <p style={{ color: '#cf1322' }}>{STEP_LABELS[resetResult.failed_step ?? ''] ?? resetResult.failed_step}</p>
                    {resetResult.assertions?.error && (
                      <Typography.Text type="secondary">{resetResult.assertions.error}</Typography.Text>
                    )}
                  </Col>
                )}
                {!resetResult.success && (
                  <Col span={8}>
                    <Typography.Text strong>待执行步骤</Typography.Text>
                    <ul>
                      {resetResult.pending_steps.map((s) => (
                        <li key={s}>{STEP_LABELS[s] ?? s}</li>
                      ))}
                    </ul>
                  </Col>
                )}
              </Row>
            </div>
          )}
        </Card>
      </Space>
    </PageContainer>
  );
}
