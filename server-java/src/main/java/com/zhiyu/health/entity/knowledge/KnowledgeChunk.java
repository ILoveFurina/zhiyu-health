package com.zhiyu.health.entity.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 知识库分块（票 89，ADR-0036）：一场景一 chunk，向量列维度 2048。
 *
 * <p>document_id 指向 knowledge_documents（可空兼容存量 seed chunk）。
 * 归档文档物理删 chunk 行，检索 SQL 无需加 JOIN 过滤（零改动）。
 * vector 列为 pgvector 类型，Java 侧只作写入载体（字符串字面量），不映射为 Java 类型。
 */
@Getter
@Setter
@TableName("knowledge_chunks")
public class KnowledgeChunk {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String department;
    private String title;
    private String content;
    private Long documentId;
    private OffsetDateTime createdAt;

    /** vector 列为 pgvector 类型，不映射为 Java 字段；写入时经 mapper 传字面量。 */
}
