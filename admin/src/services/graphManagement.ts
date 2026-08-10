import { request } from '@umijs/max';
import type { GraphEdgeType, GraphNodeLabel } from '@/contracts/graphManagement';

/** 图谱管理节点（票 91）：白名单内 Symptom/Disease/Department。 */
export interface GraphNodeItem {
  node_id: string;
  label: GraphNodeLabel;
  name: string;
  aliases?: string[];
  description?: string;
}

export interface GraphNodeListResult {
  total: number;
  items: GraphNodeItem[];
}

export interface GraphNodeInput {
  label: GraphNodeLabel;
  name: string;
  aliases?: string[];
  description?: string;
}

export interface GraphNodeUpdateInput {
  name?: string;
  aliases?: string[];
  description?: string;
}

/** 改/删节点响应：命中同名 RAG 知识块时 rag_chunk_count 非 null，B 端需弹提示（票 91 决策 3）。 */
export interface GraphNodeMutationResult {
  node: GraphNodeItem;
  rag_chunk_count: number | null;
}

export interface GraphEdgeItem {
  from_node_id: string;
  from_name: string;
  type: GraphEdgeType;
  to_node_id: string;
  to_name: string;
}

export interface GraphEdgeListResult {
  total: number;
  items: GraphEdgeItem[];
}

export interface GraphEdgeInput {
  from_node_id: string;
  to_node_id: string;
  type: GraphEdgeType;
}

// node_id 形如 "{label}:{中文 natural_key}"，进入路径段前必须编码
const enc = encodeURIComponent;

export function listGraphNodes(params: {
  label?: GraphNodeLabel;
  keyword?: string;
  page?: number;
  size?: number;
}) {
  return request<GraphNodeListResult>('/api/b/knowledge/graph/nodes', { params });
}

export function createGraphNode(data: GraphNodeInput) {
  return request<GraphNodeMutationResult>('/api/b/knowledge/graph/nodes', {
    method: 'POST',
    data,
  });
}

export function updateGraphNode(nodeId: string, data: GraphNodeUpdateInput) {
  return request<GraphNodeMutationResult>(`/api/b/knowledge/graph/nodes/${enc(nodeId)}`, {
    method: 'PUT',
    data,
  });
}

// skipErrorHandler：删除保护 409 需把 detail 中的关系计数弹成完整提示，由页面自行处理
export function deleteGraphNode(nodeId: string) {
  return request<{ rag_chunk_count: number | null }>(
    `/api/b/knowledge/graph/nodes/${enc(nodeId)}`,
    { method: 'DELETE', skipErrorHandler: true },
  );
}

export function listGraphEdges(params: {
  node_id?: string;
  type?: GraphEdgeType;
  page?: number;
  size?: number;
}) {
  return request<GraphEdgeListResult>('/api/b/knowledge/graph/edges', { params });
}

export function createGraphEdge(data: GraphEdgeInput) {
  return request<GraphEdgeItem>('/api/b/knowledge/graph/edges', { method: 'POST', data });
}

export function deleteGraphEdge(edge: GraphEdgeInput) {
  return request<void>('/api/b/knowledge/graph/edges', { method: 'DELETE', params: edge });
}
