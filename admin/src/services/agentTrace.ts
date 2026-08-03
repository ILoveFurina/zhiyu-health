import { request } from '@umijs/max';

/** 会话摘要（有 trace 的会话导航列表项）。 */
export interface ConversationTraceView {
  conversation_id: number;
  patient_id: number;
  conversation_title: string;
  last_active_at: string;
}

/** 扁平工具进度事件（每条工具进度事件一行，tool_start/tool_end 用 tool_call_id 配对）。 */
export interface AgentCallLogView {
  id: number;
  round_id: number;
  conversation_id: number;
  patient_id: number;
  tool_call_id: string | null;
  tool_name: string;
  phase: 'tool_start' | 'tool_end';
  result: 'success' | 'error' | 'skipped' | null;
  duration_ms: number | null;
  error_code: string | null;
  seq: number;
  created_at: string;
}

/** 获取有 trace 的会话摘要列表。 */
export function fetchTraceConversations() {
  return request<ConversationTraceView[]>('/api/b/agent-call-logs/conversations');
}

/** 获取指定会话的扁平事件列表（按 round_id + seq 还原顺序）。 */
export function fetchTraceLogs(conversationId: number) {
  return request<AgentCallLogView[]>('/api/b/agent-call-logs', {
    params: { conversation_id: conversationId },
  });
}
