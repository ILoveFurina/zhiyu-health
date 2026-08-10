package com.zhiyu.health.entity.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * knowledge_chunks 只读投影（票 89）：仅供图谱管理 RAG 对齐护栏按 title 计数，
 * content/vector 不映射；写入仍属 seed/离线链路，server-java 业务链路不写本表。
 */
@Getter
@Setter
@TableName("knowledge_chunks")
public class KnowledgeChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String department;
    private String title;
}
