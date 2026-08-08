import { useCallback, useEffect, useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { App, Button, Card, Drawer, Input, Skeleton, Table, Tag, Timeline, type TableColumnsType } from 'antd';
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

// 知识源类型标识：区分 RAG 检索与知识图谱遍历（用户侧区分 rag/图谱调用）
const TOOL_SOURCE_TYPE: Record<string, string> = {
  search_knowledge: 'RAG',
  traverse_graph: '知识图谱',
};

// 尝试把脱敏摘要解析成可读 JSON；非 JSON 原样返回
function formatSummary(raw: string | null): string {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

/**
 * Agent 调用日志页（票 24）。
 *
 * 两级视图：会话列表 -> 调用链明细。按 round_id + seq 还原顺序，
 * tool_call_id 配对展示 start/end。会话列显标题、患者列显昵称（去 #），
 * 支持按患者昵称筛选；每个 tool_end 可展开查看脱敏后的工具响应摘要。
 * 数据仅 admin 角色可见（server-java 就地鉴权）。
 */
export default function AgentTracePage() {
  const { message } = App.useApp();
  const [conversations, setConversations] = useState<ConversationTraceView[]>([]);
  const [loading, setLoading] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [logs, setLogs] = useState<AgentCallLogView[]>([]);
  const [activeConversation, setActiveConversation] = useState<ConversationTraceView>();
  // 已展开响应摘要的调用链行 id 集合（tool_end 行可展开看脱敏响应）
  const [expandedCalls, setExpandedCalls] = useState<Set<number>>(new Set());

  const load = useCallback(
    async (keyword?: string) => {
      setLoading(true);
      try {
        setConversations(await fetchTraceConversations(keyword));
      } catch {
        message.error('Agent 调用日志加载失败');
      } finally {
        setLoading(false);
      }
    },
    [message],
  );

  useEffect(() => {
    load().catch(() => {});
  }, [load]);

  const openDetail = async (conv: ConversationTraceView) => {
    setActiveConversation(conv);
    setDetailOpen(true);
    setDetailLoading(true);
    setExpandedCalls(new Set());
    try {
      setLogs(await fetchTraceLogs(conv.conversation_id));
    } catch {
      message.error('调用链加载失败');
      setLogs([]);
    } finally {
      setDetailLoading(false);
    }
  };

  const toggleCall = (id: number) => {
    setExpandedCalls((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const columns = useMemo<TableColumnsType<ConversationTraceView>>(
    () => [
      {
        title: '会话',
        dataIndex: 'conversation_title',
        ellipsis: true,
        render: (_, row) => (
          <div>
            <div>{row.conversation_title}</div>
            <div style={{ color: '#bfbfbf', fontSize: 12 }}>ID: {row.conversation_id}</div>
          </div>
        ),
      },
      {
        title: '患者',
        dataIndex: 'patient_nickname',
        width: 160,
        render: (_, row) => (
          <div>
            <div>{row.patient_nickname}</div>
            <div style={{ color: '#bfbfbf', fontSize: 12 }}>ID: {row.patient_id}</div>
          </div>
        ),
      },
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
        description="查看 Agent 会话与工具调用链，按 round_id 还原调用顺序；每个工具调用可展开查看脱敏响应摘要"
        tags={['会话级', '调用链明细', '按患者筛选']}
      />
      <StatCards items={[
        { label: '会话总数', value: conversations.length, suffix: '个' },
      ]} />
      <Card
        title="会话列表"
        extra={
          <Input.Search
            allowClear
            placeholder="按患者昵称筛选"
            style={{ width: 240 }}
            onSearch={(v) => {
              load(v.trim() || undefined).catch(() => {});
            }}
            onClear={() => {
              load().catch(() => {});
            }}
          />
        }
      >
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
        title={activeConversation ? `调用链明细 - ${activeConversation.conversation_title}` : '调用链明细'}
        open={detailOpen}
        onClose={() => {
          setDetailOpen(false);
          setLogs([]);
        }}
        width={680}
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
                      <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
                        <Tag color={log.phase === 'tool_start' ? 'blue' : RESULT_COLORS[log.result ?? '']}>
                          {log.phase === 'tool_start' ? '开始' : RESULT_LABELS[log.result ?? ''] ?? '结束'}
                        </Tag>
                        <span style={{ fontWeight: 500 }}>
                          {TOOL_LABELS[log.tool_name] ?? log.tool_name}
                        </span>
                        {TOOL_SOURCE_TYPE[log.tool_name] && (
                          <Tag color="geekblue">{TOOL_SOURCE_TYPE[log.tool_name]}</Tag>
                        )}
                        <code style={{ color: '#8c8c8c', fontSize: 12 }}>
                          {log.tool_name}
                        </code>
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
                      {log.phase === 'tool_end' && log.tool_output_summary && (
                        <div style={{ marginTop: 6 }}>
                          <Button
                            size="small"
                            type="link"
                            style={{ paddingLeft: 0 }}
                            onClick={() => toggleCall(log.id)}
                          >
                            {expandedCalls.has(log.id) ? '收起响应' : '查看响应'}
                          </Button>
                          {expandedCalls.has(log.id) && (
                            <pre
                              style={{
                                background: '#fafafa',
                                border: '1px solid #f0f0f0',
                                borderRadius: 4,
                                padding: 8,
                                fontSize: 12,
                                maxHeight: 260,
                                overflow: 'auto',
                                whiteSpace: 'pre-wrap',
                                wordBreak: 'break-all',
                              }}
                            >
                              {formatSummary(log.tool_output_summary)}
                            </pre>
                          )}
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
