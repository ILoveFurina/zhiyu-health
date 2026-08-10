import contract from '../../../contracts/graph-management.json';

// 图谱在线管理白名单（票 89 / ADR-0006 修订）：可在线编辑的节点 label、
// 各 label 可编辑属性、关系类型及两端 label 组合。TS 类型与表单选项均从
// contracts/graph-management.json 推导，与 server-java 强校验同源。
export type GraphNodeLabel = keyof typeof contract.editable_properties;
export const graphNodeLabels = contract.node_labels as GraphNodeLabel[];
export const graphEditableProperties = contract.editable_properties as Record<GraphNodeLabel, string[]>;

export type GraphEdgeType = keyof typeof contract.edge_types;
export const graphEdgeTypes = Object.keys(contract.edge_types) as GraphEdgeType[];
export const graphEdgeEndpoints = contract.edge_types as Record<
  GraphEdgeType,
  { from_label: GraphNodeLabel; to_label: GraphNodeLabel }
>;

// UI 中文名（展示层常量，非契约值）
export const graphNodeLabelNames: Record<GraphNodeLabel, string> = {
  Symptom: '症状',
  Disease: '疾病',
  Department: '科室',
};

export const graphEdgeTypeNames: Record<GraphEdgeType, string> = {
  INDICATES: '关联疾病',
  TREATED_BY: '归属科室',
  SUGGESTS_DEPARTMENT: '建议科室',
};
