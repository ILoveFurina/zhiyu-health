package com.zhiyu.health.service.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.agentclient.AgentClient.EmbedTextItem;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.knowledge.KnowledgeDocument;
import com.zhiyu.health.mapper.knowledge.KnowledgeChunkMapper;
import com.zhiyu.health.mapper.knowledge.KnowledgeChunkMapper.ChunkInsert;
import com.zhiyu.health.mapper.knowledge.KnowledgeDocumentMapper;
import com.zhiyu.health.service.common.MinioStorageService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识文档上传闭环（票 89，ADR-0036）。
 *
 * <p>承载上传 -> 异步解析切分 -> 调 server-py embedding -> 写 knowledge_chunks 的完整链路。
 * 文档状态机四态 PROCESSING/READY/FAILED/ARCHIVED；seed 文档只读不可归档/重试/重切。
 * 归档连带物理删 chunk 行，运行时检索 SQL 零改动。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocumentService extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocument> {

    private final MinioStorageService minioStorage;
    private final AgentClient agentClient;
    private final Contracts contracts;
    private final KnowledgeChunkMapper chunkMapper;

    // ============ 状态常量（与契约 document_status 枚举一致） ============
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    private static final String SOURCE_SEED = "SEED";
    private static final String SOURCE_UPLOAD = "UPLOAD";

    private static final String ERROR_ORPHANED = "ORPHANED";
    private static final String ERROR_PARSE_FAILED = "KNOWLEDGE_DOCUMENT_PARSE_FAILED";

    /**
     * 上传文档：存 MinIO（降级空）+ 落库 PROCESSING + 提交异步任务。
     *
     * @return 新建的文档元数据（status=PROCESSING）
     */
    @Transactional
    public KnowledgeDocument upload(MultipartFile file, String department, long staffId) {
        validateUpload(file);
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "untitled.txt";
        String contentType = file.getContentType() != null ? file.getContentType() : "text/plain";

        // MinIO 旁路存储：不可用时 object_key 置空降级，元数据与 chunk 仍可写库
        Optional<String> objectKey = minioStorage.storeDocument(file);

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setFileName(fileName);
        doc.setContentType(contentType);
        doc.setByteSize(file.getSize());
        doc.setObjectKey(objectKey.orElse(null));
        doc.setSource(SOURCE_UPLOAD);
        doc.setStatus(STATUS_PROCESSING);
        doc.setDepartment(department);
        doc.setUploaderStaffId(staffId);
        doc.setProcessingStartedAt(OffsetDateTime.now());
        doc.setChunkCount(0);
        save(doc);

        // 提交异步处理任务；队列满时 DiscardPolicy 静默丢弃，文档保持 PROCESSING 由孤儿扫描恢复
        processAsync(doc.getId());
        return doc;
    }

    /**
     * 异步处理：解析 -> 切分 -> 调 embedding -> 写 chunk。
     *
     * <p>任一步失败标 FAILED + error_code/error_message，保留原文与元数据供重试。
     * 队列满被拒绝时文档保持 PROCESSING，由启动时孤儿扫描恢复。
     */
    @Async("knowledgeDocumentExecutor")
    public void processAsync(long documentId) {
        try {
            processInternal(documentId);
        } catch (ApiException e) {
            markFailed(documentId, e.getCode() != null ? e.getCode() : ERROR_PARSE_FAILED, e.getMessage());
        } catch (Exception e) {
            log.error("知识文档异步处理失败 documentId={}", documentId, e);
            markFailed(documentId, ERROR_PARSE_FAILED, "文档处理失败：" + e.getMessage());
        }
    }

    /** 同步处理逻辑（供异步调用与重试复用）。 */
    private void processInternal(long documentId) {
        KnowledgeDocument doc = getById(documentId);
        if (doc == null) {
            throw new ApiException(404, "KNOWLEDGE_DOCUMENT_NOT_FOUND", "知识文档不存在");
        }
        if (SOURCE_SEED.equals(doc.getSource())) {
            throw new ApiException(403, "KNOWLEDGE_DOCUMENT_SEED_READONLY", "系统预置文档不可重切");
        }

        // 标 PROCESSING + 刷新 processing_started_at（重试场景从 FAILED 回到 PROCESSING）
        doc.setStatus(STATUS_PROCESSING);
        doc.setProcessingStartedAt(OffsetDateTime.now());
        doc.setErrorCode(null);
        doc.setErrorMessage(null);
        updateById(doc);

        // 1. 读取原文（MinIO 旁路；object_key 空时从上传文件直接取字节缓存不可行，须标记 FAILED）
        String text = readDocumentText(doc);
        if (text == null || text.isBlank()) {
            throw new ApiException(422, ERROR_PARSE_FAILED, "文档内容为空");
        }

        // 2. 滑动窗口切分（chunk_size/overlap 读契约）
        Contracts.Chunking chunking = contracts.knowledgeDocuments().chunking();
        List<TextChunk> chunks = splitText(text, chunking.chunkSize(), chunking.chunkOverlap());
        if (chunks.isEmpty()) {
            throw new ApiException(422, ERROR_PARSE_FAILED, "文档切分后无有效段落");
        }

        // 3. 调 server-py 批量 embedding（title={文档标题} - 第{n}段，department 文档级继承）
        String titleTemplate = contracts.knowledgeDocuments().titleFormat();
        List<EmbedTextItem> embedItems = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String title = titleTemplate
                    .replace("{document_title}", stripExtension(doc.getFileName()))
                    .replace("{n}", String.valueOf(i + 1));
            embedItems.add(new EmbedTextItem(title, chunks.get(i).content));
        }
        List<float[]> vectors = agentClient.embedKnowledgeTexts(embedItems);
        if (vectors.size() != chunks.size()) {
            throw new ApiException(502, "EMBEDDING_MODEL_FAILED", "向量数量与切分段数不一致");
        }

        // 4. 事务内写 chunk（先删后写，幂等重试）+ 更新文档状态 READY
        writeChunksAndMarkReady(documentId, doc.getDepartment(), embedItems, vectors);
    }

    /** 事务内写 chunk + 更新文档状态 READY（先删后写保证幂等重试安全）。 */
    @Transactional
    public void writeChunksAndMarkReady(
            long documentId, String department, List<EmbedTextItem> embedItems, List<float[]> vectors) {
        // 先删该文档已写 chunk（重试场景的幂等保证：先删后写）
        chunkMapper.deleteByDocumentId(documentId);

        List<ChunkInsert> inserts = new ArrayList<>();
        for (int i = 0; i < embedItems.size(); i++) {
            inserts.add(new ChunkInsert(
                    department,
                    embedItems.get(i).title(),
                    embedItems.get(i).content(),
                    vectorLiteral(vectors.get(i)),
                    documentId));
        }
        chunkMapper.batchInsertWithVector(inserts);

        KnowledgeDocument doc = getById(documentId);
        if (doc != null) {
            doc.setStatus(STATUS_READY);
            doc.setChunkCount(inserts.size());
            doc.setErrorCode(null);
            doc.setErrorMessage(null);
            doc.setUpdatedAt(OffsetDateTime.now());
            updateById(doc);
        }
    }

    /**
     * 重试：先删已写 chunk 再整链重跑（幂等）。
     * FAILED/ARCHIVED 文档可重试；PROCESSING 拒绝（防并发）；SEED 拒绝。
     */
    public void retry(long documentId) {
        KnowledgeDocument doc = getById(documentId);
        if (doc == null) {
            throw new ApiException(404, "KNOWLEDGE_DOCUMENT_NOT_FOUND", "知识文档不存在");
        }
        if (SOURCE_SEED.equals(doc.getSource())) {
            throw new ApiException(403, "KNOWLEDGE_DOCUMENT_SEED_READONLY", "系统预置文档不可重试");
        }
        if (STATUS_PROCESSING.equals(doc.getStatus())) {
            throw new ApiException(409, "KNOWLEDGE_DOCUMENT_INVALID_STATE", "文档正在处理中，请稍后");
        }
        processAsync(documentId);
    }

    /**
     * 归档：物理删该文档全部 chunk 行 + 文档 status=ARCHIVED（元数据 + MinIO 原文保留）。
     * 检索 SQL 零改动（chunk 行不存在即自然过滤）。SEED 文档拒绝归档。
     */
    @Transactional
    public void archive(long documentId) {
        KnowledgeDocument doc = getById(documentId);
        if (doc == null) {
            throw new ApiException(404, "KNOWLEDGE_DOCUMENT_NOT_FOUND", "知识文档不存在");
        }
        if (SOURCE_SEED.equals(doc.getSource())) {
            throw new ApiException(403, "KNOWLEDGE_DOCUMENT_SEED_READONLY", "系统预置文档不可归档");
        }
        chunkMapper.deleteByDocumentId(documentId);
        doc.setStatus(STATUS_ARCHIVED);
        doc.setChunkCount(0);
        doc.setUpdatedAt(OffsetDateTime.now());
        updateById(doc);
    }

    /** 列表：含 seed + upload，按创建时间降序。 */
    public List<KnowledgeDocument> listAll() {
        return list(new QueryWrapper<KnowledgeDocument>().orderByDesc("created_at"));
    }

    /**
     * 启动时孤儿恢复：扫描 status=PROCESSING 且 processing_started_at 超时（600s）的记录标 FAILED。
     * 单实例拓扑下启动时一次性扫描即可（ADR-0036 决策第 6 点）。
     */
    public int recoverOrphanedProcessing() {
        int timeoutSeconds = contracts.knowledgeDocuments().orphanTimeoutSeconds();
        OffsetDateTime threshold = OffsetDateTime.now().minusSeconds(timeoutSeconds);
        List<KnowledgeDocument> orphans = list(new QueryWrapper<KnowledgeDocument>()
                .eq("status", STATUS_PROCESSING)
                .lt("processing_started_at", threshold));
        for (KnowledgeDocument doc : orphans) {
            markFailed(doc.getId(), ERROR_ORPHANED, "处理超时未完成，可能因进程崩溃或队列拒绝");
            log.warn("孤儿文档恢复：documentId={} 标记为 ORPHANED", doc.getId());
        }
        return orphans.size();
    }

    // ============ 内部工具 ============

    private void markFailed(long documentId, String errorCode, String errorMessage) {
        KnowledgeDocument doc = getById(documentId);
        if (doc == null) {
            return;
        }
        doc.setStatus(STATUS_FAILED);
        doc.setErrorCode(errorCode);
        doc.setErrorMessage(errorMessage);
        doc.setUpdatedAt(OffsetDateTime.now());
        updateById(doc);
    }

    private String readDocumentText(KnowledgeDocument doc) {
        // MinIO 旁路：object_key 非空时从 MinIO 回拉；空时无法重读，标记 FAILED
        if (doc.getObjectKey() == null || doc.getObjectKey().isBlank()) {
            return null;
        }
        Optional<java.io.InputStream> stream = minioStorage.getDocumentStream(doc.getObjectKey());
        if (stream.isEmpty()) {
            return null;
        }
        try (var input = stream.get()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("文档原文读取失败 documentId={}: {}", doc.getId(), e.getMessage());
            return null;
        }
    }

    /** 上传校验：type/size 读契约。 */
    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(400, "KNOWLEDGE_EMBEDDING_INVALID", "请选择文档文件");
        }
        Contracts.Upload upload = contracts.knowledgeDocuments().upload();
        if (file.getSize() > upload.maxFileBytes()) {
            throw new ApiException(400, "KNOWLEDGE_EMBEDDING_INVALID", "文件不能超过 2MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !upload.allowedTypes().contains(contentType)) {
            throw new ApiException(400, "KNOWLEDGE_EMBEDDING_INVALID", "仅支持纯文本和 Markdown 格式");
        }
    }

    /**
     * 滑动窗口切分：按 chunk_size 字符切分，chunk_overlap 重叠。
     * 空文档返回空列表；超长单段按 chunk_size 强制截断。
     */
    static List<TextChunk> splitText(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // 归一化换行：Markdown/纯文本的 \r\n -> \n
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n").strip();
        if (normalized.isEmpty()) {
            return List.of();
        }
        int step = Math.max(1, chunkSize - overlap);
        List<TextChunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + chunkSize, normalized.length());
            String segment = normalized.substring(start, end).strip();
            if (!segment.isEmpty()) {
                chunks.add(new TextChunk(segment));
            }
            if (end >= normalized.length()) {
                break;
            }
            start += step;
        }
        return chunks;
    }

    /** pgvector 文本字面量：'[v1,v2,...]' 保留 8 位小数（与 seed_embeddings.py 一致）。 */
    static String vectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("%.8f", vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) {
            return "文档";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 切分产出的文本段（纯内容，title 在调用方按段号拼接）。 */
    record TextChunk(String content) {}
}
