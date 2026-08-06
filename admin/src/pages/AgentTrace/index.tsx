import { useCallback, useEffect, useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { App, Card, Drawer, Skeleton, Table, Tag, Timeline, type TableColumnsType } from 'antd';
import {
  fetchTraceConversations,
  fetchTraceLogs,
  type AgentCallLogView,
  type ConversationTraceView,
} from '@/services/agentTrace';
import StatCards from '@/components/StatCards';
import PageHead from '@/components/PageHead';

// tool_end 结果枚举配色（success/error/skipped）
const RESULT_COLORS: Record<string, string> = {
  success: 'green',
  error: 'red',
  skipped: 'default',
};

const RESULT_LABELS: Record<string, string> = {
  success: '成功',
  error: '失败',
  skipped: '跳过',
};

// 工具名->中文文案（与 miniprogram TOOL_LABELS 对齐）
const TOOL_LABELS: Record<string, string> = {
  recommend_doctors: '推荐医生',
  get_doctor_slots: '查询号源',
  find_hospitals: '查找医院',
  create_appointment: '挂号',
  get_appointment: '查询挂号',
  search_knowledge: '知识检索',
  traverse_graph: '图谱遍历',
};

/**
 * Agent 调用日志页（票 24）。
 *
 * 两级视图：会话列表 -> 调用链明细。按 round_id + seq 还原顺序，
 * tool_call_id 配对展示 start/end。数据仅 admin 角色可见（server-java 就地鉴权）。
 */
export default function AgentTracePage() {
  const { message } = App.useApp();
  const [conversations, setConversations] = useState<ConversationTraceView[]>([]);
  const [loading, setLoading] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [logs, setLogs] = useState<AgentCallLogView[]>([]);
  const [activeConversation, setActiveConversation] = useState<ConversationTraceView>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setConversations(await fetchTraceConversations());
    } catch {
      message.error('Agent 调用日志加载失败');
    } finally {
      setLoading(false);
    }
  }, [message]);

  useEffect(() => {
    load().catch(() => {});
  }, [load]);

  const openDetail = async (conv: ConversationTraceView) => {
    setActiveConversation(conv);
    setDetailOpen(true);
    setDetailLoading(true);
    try {
      setLogs(await fetchTraceLogs(conv.conversation_id));
    } catch {
      message.error('调用链加载失败');
      setLogs([]);
    } finally {
      setDetailLoading(false);
    }
  };

  const columns = useMemo<TableColumnsType<ConversationTraceView>>(
    () => [
      {
        title: '会话',
        dataIndex: 'conversation_id',
        width: 100,
        render: (value) => `#${value}`,
      },
      { title: '标题', dataIndex: 'conversation_title', ellipsis: true },
      { title: '患者', dataIndex: 'patient_id', width: 100, render: (value) => `#${value}` },
      {
        title: '最近活跃',
        dataIndex: 'last_active_at',
        width: 220,
        render: (value) => value || '-',
      },
      {
        title: '操作',
        width: 140,
        render: (_, row) => (
          <a onClick={() => openDetail(row)}>查看调用链</a>
        ),
      },
    ],
    [],
  );

  // 按 round_id 分组，每组内按 seq 排序，还原工具调用链顺序
  const groupedLogs = useMemo(() => {
    const groups = new Map<number, AgentCallLogView[]>();
    for (const log of logs) {
      const list = groups.get(log.round_id) ?? [];
      list.push(log);
      groups.set(log.round_id, list);
    }
    return Array.from(groups.entries()).sort((a, b) => a[0] - b[0]);
  }, [logs]);

  return (
    <PageContainer header={{ title: null }}>
      <PageHead
        title="Agent 调用日志"
        description="查看 Agent 会话与工具调用链，按 round_id 还原调用顺序"
        tags={['会话级', '调用链明细']}
      />
      <StatCards items={[
        { label: '会话总数', value: conversations.length, suffix: '个' },
        { label: '当前查看', value: activeConversation ? `#${activeConversation.conversation_id}` : '-' },
      ]} />
      <Card title="会话列表">
        <Table
          rowKey="conversation_id"
          columns={columns}
          dataSource={conversations}
          loading={loading}
          pagination={{ pageSize: 20, showSizeChanger: false }}
          size="small"
        />
      </Card>

      <Drawer
        title={activeConversation ? `调用链明细 - 会话 #${activeConversation.conversation_id}` : '调用链明细'}
        open={detailOpen}
        onClose={() => {
          setDetailOpen(false);
          setLogs([]);
        }}
        width={640}
      >
        {detailLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} />
        ) : logs.length === 0 ? (
          <div style={{ color: '#8c8c8c', paddingTop: 40, textAlign: 'center' }}>
            该会话暂无调用日志
          </div>
        ) : (
          groupedLogs.map(([roundId, roundLogs]) => (
            <div key={roundId} style={{ marginBottom: 24 }}>
              <div style={{ fontWeight: 600, marginBottom: 12, color: '#595959' }}>
                轮次 #{roundId}
              </div>
              <Timeline
                items={roundLogs.map((log) => ({
                  color:
                    log.phase === 'tool_start'
                      ? 'blue'
                      : RESULT_COLORS[log.result ?? ''] ?? 'gray',
                  children: (
                    <div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <Tag color={log.phase === 'tool_start' ? 'blue' : RESULT_COLORS[log.result ?? '']}>
                          {log.phase === 'tool_start' ? '开始' : RESULT_LABELS[log.result ?? ''] ?? '结束'}
                        </Tag>
                        <span style={{ fontWeight: 500 }}>
                          {TOOL_LABELS[log.tool_name] ?? log.tool_name}
                        </span>
                        {log.duration_ms != null && (
                          <span style={{ color: '#8c8c8c', fontSize: 12 }}>{log.duration_ms}ms</span>
                        )}
                      </div>
                      {log.tool_call_id && (
                        <div style={{ color: '#bfbfbf', fontSize: 12, marginTop: 2 }}>
                          call_id: {log.tool_call_id}
                        </div>
                      )}
                      {log.error_code && (
                        <div style={{ color: '#cf1322', fontSize: 12, marginTop: 2 }}>
                          错误码: {log.error_code}
                        </div>
                      )}
                    </div>
                  ),
                }))}
              />
            </div>
          ))
        )}
      </Drawer>
    </PageContainer>
  );
}
