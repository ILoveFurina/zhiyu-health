package com.zhiyu.health.service.knowledge;

/** 图谱关系视图：两端 node_id + 名称快照（B 端表格直接展示，免去逐个回查节点详情）。 */
public record GraphEdgeView(String fromNodeId, String fromName, String type, String toNodeId, String toName) {}
