package com.zhiyu.health.service.knowledge;

import java.util.List;

/**
 * 图谱节点编辑属性（票 89）：name 为自然键；aliases/description 是否可编辑由
 * contracts/graph-management.json 的 editable_properties 白名单按 label 限定。
 * 更新语义：字段为 null 表示不改动，显式提供（含空值）才覆盖。
 */
public record GraphNodeProps(String name, List<String> aliases, String description) {}
