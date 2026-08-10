package com.zhiyu.health.service.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 知识文档 HTTP 视图（票 89，ADR-0036）。 */
public final class KnowledgeDocumentViews {
    private KnowledgeDocumentViews() {}

    /** 文档列表项：含 seed + upload，返回 status/chunk_count/source 供前端展示。 */
    public record DocumentView(
            Long id,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("content_type") String contentType,
            @JsonProperty("byte_size") Long byteSize,
            String source,
            String status,
            String department,
            @JsonProperty("chunk_count") Integer chunkCount,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("error_message") String errorMessage,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt) {}

    /** 上传响应：文档 id + status=PROCESSING，前端据此轮询列表。 */
    public record UploadResponse(Long id, String status) {}
}
