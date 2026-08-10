package com.zhiyu.health.service.knowledge;

/**
 * 节点写操作结果（票 91 grilling 决策 3）：ragChunkCount 为 RAG 对齐护栏——改名/删除
 * Symptom 命中 PG knowledge_chunks 同名 title 时带回计数，由 B 端提示人工同步；
 * 不适用（非 Symptom、未改名）或无命中时为 null。绝不因此阻断或联动双写。
 */
public record GraphNodeResult(GraphNodeView node, Long ragChunkCount) {}
