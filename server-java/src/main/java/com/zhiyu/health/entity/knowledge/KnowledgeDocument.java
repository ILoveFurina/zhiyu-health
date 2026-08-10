package com.zhiyu.health.entity.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 知识文档元数据与异步处理状态机（票 89，ADR-0036）。
 *
 * <p>source=SEED 为系统预置（只读，永远 READY），source=UPLOAD 为运营上传。
 * status 四态 PROCESSING/READY/FAILED/ARCHIVED；原文旁路存 MinIO（object_key 降级空）。
 * processing_started_at 用于启动时孤儿恢复扫描（超时标 ORPHANED）。
 */
@Getter
@Setter
@TableName("knowledge_documents")
public class KnowledgeDocument {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String fileName;
    private String contentType;
    private Long byteSize;
    private String objectKey;
    private String source;
    private String status;
    private String department;
    private Long uploaderStaffId;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime processingStartedAt;
    private Integer chunkCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
