import { request } from '@umijs/max';

/** 科室分布项。 */
export interface DepartmentShare {
  department: string;
  count: number;
}

/** 号源使用率。 */
export interface SlotUsage {
  rate: number;
}

/** Agent 对话量与工具调用次数。 */
export interface AgentActivity {
  chat_rounds: number;
  tool_calls: number;
}

/** 演示看板聚合视图（严格四类指标）。 */
export interface DashboardView {
  today_appointments: number;
  department_distribution: DepartmentShare[];
  slot_usage: SlotUsage;
  agent_activity: AgentActivity;
}

/** 知识源现场切换视图。 */
export interface KnowledgeSourceView {
  knowledge_source: string;
}

/** 演示重置结果（成功与失败统一形状）。 */
export interface ResetResult {
  success: boolean;
  completed_steps: string[];
  failed_step: string | null;
  pending_steps: string[];
  frozen_after: boolean;
  assertions: Record<string, string>;
}

/** 获取演示看板聚合数据。 */
export function fetchDashboard() {
  return request<DashboardView>('/api/b/demo/dashboard');
}

/** 读知识源现场切换全局键。 */
export function fetchKnowledgeSource() {
  return request<KnowledgeSourceView>('/api/b/demo/knowledge-source');
}

/** 写知识源现场切换全局键。 */
export function putKnowledgeSource(knowledgeSource: string) {
  return request<KnowledgeSourceView>('/api/b/demo/knowledge-source', {
    method: 'PUT',
    data: { knowledge_source: knowledgeSource },
  });
}

/** 执行演示重置（三重保护 + 七步顺序）。 */
export function resetDemo(confirm: string) {
  return request<ResetResult>('/api/b/demo/reset', {
    method: 'POST',
    data: { confirm },
    skipErrorHandler: true,
  });
}
