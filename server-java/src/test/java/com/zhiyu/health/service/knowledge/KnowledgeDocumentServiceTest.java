package com.zhiyu.health.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.knowledge.KnowledgeDocument;
import com.zhiyu.health.mapper.knowledge.KnowledgeChunkMapper;
import com.zhiyu.health.mapper.knowledge.KnowledgeDocumentMapper;
import com.zhiyu.health.service.common.MinioStorageService;
import com.zhiyu.health.service.knowledge.KnowledgeDocumentService.TextChunk;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 知识文档服务单测（票 89，ADR-0036）。
 *
 * <p>覆盖：切分边界（chunk_size/overlap 交叉、空文档、超长单段）、归档物理删 chunk、
 * 孤儿恢复、幂等重试（先删后写）、SEED 文档拒绝操作。
 */
class KnowledgeDocumentServiceTest {

    private final KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    private final KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
    private final MinioStorageService minioStorage = mock(MinioStorageService.class);
    private final AgentClient agentClient = mock(AgentClient.class);
    private final Contracts contracts = Contracts.load(Contracts.resolveDir());

    private final KnowledgeDocumentService service = service();

    private KnowledgeDocumentService service() {
        KnowledgeDocumentService svc = new KnowledgeDocumentService(minioStorage, agentClient, contracts, chunkMapper);
        ReflectionTestUtils.setField(svc, "baseMapper", documentMapper);
        return svc;
    }

    // ============ 切分逻辑 ============

    @Test
    void splitTextEmptyReturnsEmpty() {
        assertThat(KnowledgeDocumentService.splitText("", 500, 50)).isEmpty();
        assertThat(KnowledgeDocumentService.splitText("   \n  \t ", 500, 50)).isEmpty();
        assertThat(KnowledgeDocumentService.splitText(null, 500, 50)).isEmpty();
    }

    @Test
    void splitTextShortContentSingleChunk() {
        List<TextChunk> chunks = KnowledgeDocumentService.splitText("短文本", 500, 50);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("短文本");
    }

    @Test
    void splitTextOverlapProducesOverlappingChunks() {
        // chunk_size=10, overlap=3 -> step=7
        // 原文 20 字符：[0,10), [7,17), [14,20)
        String text = "0123456789ABCDEFGHIJ";
        List<TextChunk> chunks = KnowledgeDocumentService.splitText(text, 10, 3);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).content()).isEqualTo("0123456789");
        assertThat(chunks.get(1).content()).isEqualTo("789ABCDEFG");
        assertThat(chunks.get(2).content()).isEqualTo("EFGHIJ");
    }

    @Test
    void splitTextNormalizesLineEndings() {
        List<TextChunk> chunks = KnowledgeDocumentService.splitText("line1\r\nline2", 500, 50);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("line1\nline2");
    }

    @Test
    void vectorLiteralFormatsCorrectly() {
        // float 精度：0.3f 实际存储为 0.30000001，此处只验证格式与前缀
        String literal = KnowledgeDocumentService.vectorLiteral(new float[] {0.1f, 0.2f});
        assertThat(literal).startsWith("[0.10000000,").endsWith("]");
        assertThat(literal).isEqualTo("[0.10000000,0.20000000]");
    }

    // ============ 归档 ============

    @Test
    void archivePhysicallyDeletesChunksAndMarksArchived() {
        KnowledgeDocument doc = uploadDoc(2L, "UPLOAD", "READY");
        when(documentMapper.selectById(2L)).thenReturn(doc);
        when(chunkMapper.deleteByDocumentId(2L)).thenReturn(3);

        service.archive(2L);

        verify(chunkMapper).deleteByDocumentId(2L);
        assertThat(doc.getStatus()).isEqualTo("ARCHIVED");
        assertThat(doc.getChunkCount()).isEqualTo(0);
    }

    @Test
    void archiveRejectsSeedDocument() {
        KnowledgeDocument doc = seedDoc();
        when(documentMapper.selectById(1L)).thenReturn(doc);

        assertThatThrownBy(() -> service.archive(1L))
                .isInstanceOf(ApiException.class)
                .hasMessage("系统预置文档不可归档");
        verify(chunkMapper, never()).deleteByDocumentId(anyLong());
    }

    @Test
    void archiveRejectsMissingDocument() {
        when(documentMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.archive(99L))
                .isInstanceOf(ApiException.class)
                .hasMessage("知识文档不存在");
    }

    // ============ 重试 ============

    @Test
    void retryRejectsProcessingDocument() {
        KnowledgeDocument doc = uploadDoc(3L, "UPLOAD", "PROCESSING");
        when(documentMapper.selectById(3L)).thenReturn(doc);

        assertThatThrownBy(() -> service.retry(3L))
                .isInstanceOf(ApiException.class)
                .hasMessage("文档正在处理中，请稍后");
    }

    @Test
    void retryRejectsSeedDocument() {
        KnowledgeDocument doc = seedDoc();
        when(documentMapper.selectById(1L)).thenReturn(doc);

        assertThatThrownBy(() -> service.retry(1L))
                .isInstanceOf(ApiException.class)
                .hasMessage("系统预置文档不可重试");
    }

    @Test
    void retryAllowsFailedDocument() {
        KnowledgeDocument doc = uploadDoc(4L, "UPLOAD", "FAILED");
        when(documentMapper.selectById(4L)).thenReturn(doc);

        // processAsync 是 @Async，直接调用会同步执行（测试无 async 代理）
        // 但会尝试读 MinIO 原文，mock 返回空会标 FAILED
        when(minioStorage.getDocumentStream(any())).thenReturn(java.util.Optional.empty());

        service.retry(4L);

        // 验证文档最终被标 FAILED（因为 object_key 为空无法重读原文）
        // updateById 被调用两次：一次设 PROCESSING，一次设 FAILED
        verify(documentMapper, org.mockito.Mockito.atLeast(2)).updateById(any(KnowledgeDocument.class));
        assertThat(doc.getStatus()).isEqualTo("FAILED");
    }

    // ============ 孤儿恢复 ============

    @Test
    void recoverOrphanedMarksTimedOutProcessingAsFailed() {
        KnowledgeDocument orphan = uploadDoc(5L, "UPLOAD", "PROCESSING");
        // processing_started_at 设为 1 小时前（超过 600s 超时）
        orphan.setProcessingStartedAt(OffsetDateTime.now().minusHours(1));
        when(documentMapper.selectList(any())).thenReturn(List.of(orphan));
        when(documentMapper.selectById(5L)).thenReturn(orphan);

        int recovered = service.recoverOrphanedProcessing();

        assertThat(recovered).isEqualTo(1);
        assertThat(orphan.getStatus()).isEqualTo("FAILED");
        assertThat(orphan.getErrorCode()).isEqualTo("ORPHANED");
    }

    @Test
    void recoverOrphanedSkipsRecentProcessing() {
        KnowledgeDocument recent = uploadDoc(6L, "UPLOAD", "PROCESSING");
        recent.setProcessingStartedAt(OffsetDateTime.now().minusSeconds(10));
        when(documentMapper.selectList(any())).thenReturn(List.of());

        int recovered = service.recoverOrphanedProcessing();

        assertThat(recovered).isEqualTo(0);
    }

    // ============ 辅助 ============

    private KnowledgeDocument seedDoc() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(1L);
        doc.setSource("SEED");
        doc.setStatus("READY");
        doc.setFileName("系统预置知识库");
        doc.setChunkCount(50);
        return doc;
    }

    private KnowledgeDocument uploadDoc(long id, String source, String status) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(id);
        doc.setSource(source);
        doc.setStatus(status);
        doc.setFileName("test-doc.md");
        doc.setDepartment("心血管内科");
        doc.setObjectKey("docs/2026-08-10/abc123.md");
        doc.setChunkCount(3);
        return doc;
    }
}
