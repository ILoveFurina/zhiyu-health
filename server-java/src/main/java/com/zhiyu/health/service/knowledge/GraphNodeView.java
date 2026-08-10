package com.zhiyu.health.service.knowledge;

import java.util.List;

/** 图谱节点视图：node_id 为 {label}:{name} 复合形式（ADR-0013 决策 6），label 用 Neo4j 原值（Symptom 等）。 */
public record GraphNodeView(String nodeId, String label, String name, List<String> aliases, String description) {}
