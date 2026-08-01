import { request } from '@umijs/max';

/** 图谱投影节点（ADR-0013 决策 6）：最小拓扑骨架，不携带节点属性。 */
export interface GraphNode {
  id: string;
  label: string;
  group: string;
}

/** 图谱投影边：source/target 为节点 id，type 为关系类型。 */
export interface GraphEdge {
  source: string;
  target: string;
  type: string;
}

/** 图谱投影骨架 {nodes, edges}。 */
export interface GraphProjection {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

/** 节点详情：点击节点时另取（属性不塞进投影）。 */
export interface GraphNodeDetail {
  node_id: string;
  node_type: string;
  name?: string;
  aliases?: string[];
  description?: string;
  ingredients?: string[];
  allergen?: string;
  department?: string;
  name_snapshot?: string;
  medication_id?: number;
}

/** 获取全图最小拓扑骨架。 */
export function fetchGraphProjection() {
  return request<GraphProjection>('/api/b/knowledge/graph');
}

/** 点击节点取详情。 */
export function fetchGraphNodeDetail(nodeId: string) {
  return request<GraphNodeDetail>('/api/b/knowledge/graph/node', {
    params: { node_id: nodeId },
  });
}
