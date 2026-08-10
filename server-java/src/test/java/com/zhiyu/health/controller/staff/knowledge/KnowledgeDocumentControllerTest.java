package com.zhiyu.health.controller.staff.knowledge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.controller.staff.knowledge.mapping.KnowledgeDocumentViewMapper;
import com.zhiyu.health.entity.knowledge.KnowledgeDocument;
import com.zhiyu.health.service.knowledge.KnowledgeDocumentService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** 知识文档管理 B 端 MockMvc 主链路冒烟（票 89，ADR-0036）。 */
class KnowledgeDocumentControllerTest {

    private final KnowledgeDocumentService service = mock(KnowledgeDocumentService.class);
    private final KnowledgeDocumentViewMapper viewMapper = Mappers.getMapper(KnowledgeDocumentViewMapper.class);
    private final MockMvc mvc = standaloneSetup(new KnowledgeDocumentController(service, viewMapper))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    private KnowledgeDocument seedDoc;
    private KnowledgeDocument uploadDoc;

    @BeforeEach
    void setup() {
        seedDoc = new KnowledgeDocument();
        seedDoc.setId(1L);
        seedDoc.setFileName("系统预置知识库（50 场景）");
        seedDoc.setContentType("text/plain");
        seedDoc.setByteSize(0L);
        seedDoc.setSource("SEED");
        seedDoc.setStatus("READY");
        seedDoc.setDepartment(null);
        seedDoc.setChunkCount(50);
        seedDoc.setCreatedAt(OffsetDateTime.now());
        seedDoc.setUpdatedAt(OffsetDateTime.now());

        uploadDoc = new KnowledgeDocument();
        uploadDoc.setId(2L);
        uploadDoc.setFileName("高血压护理指南.md");
        uploadDoc.setContentType("text/markdown");
        uploadDoc.setByteSize(1024L);
        uploadDoc.setSource("UPLOAD");
        uploadDoc.setStatus("PROCESSING");
        uploadDoc.setDepartment("心血管内科");
        uploadDoc.setChunkCount(0);
        uploadDoc.setCreatedAt(OffsetDateTime.now());
        uploadDoc.setUpdatedAt(OffsetDateTime.now());
    }

    @Test
    void listReturnsSeedAndUploadDocuments() throws Exception {
        when(service.listAll()).thenReturn(List.of(uploadDoc, seedDoc));

        mvc.perform(get("/api/b/knowledge-documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].source").value("UPLOAD"))
                .andExpect(jsonPath("$[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$[1].id").value(1))
                .andExpect(jsonPath("$[1].source").value("SEED"))
                .andExpect(jsonPath("$[1].status").value("READY"))
                .andExpect(jsonPath("$[1].chunk_count").value(50));
    }

    @Test
    void uploadCreatesDocumentAndReturnsProcessing() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "高血压护理指南.md", "text/markdown", "# 高血压护理\n低盐饮食".getBytes());
        when(service.upload(any(), anyString(), anyLong())).thenReturn(uploadDoc);

        mvc.perform(multipartWithAuth("/api/b/knowledge-documents").file(file).param("department", "心血管内科"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(service).upload(any(), anyString(), anyLong());
    }

    @Test
    void uploadRejectsMissingDepartment() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.md", "text/markdown", "content".getBytes());

        mvc.perform(multipartWithAuth("/api/b/knowledge-documents").file(file)).andExpect(status().isBadRequest());
    }

    @Test
    void retryReturnsProcessing() throws Exception {
        mvc.perform(postWithAuth("/api/b/knowledge-documents/2/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(service).retry(2L);
    }

    @Test
    void retryPropagatesSeedReadonlyError() throws Exception {
        doThrow(new ApiException(403, "KNOWLEDGE_DOCUMENT_SEED_READONLY", "系统预置文档不可重试"))
                .when(service)
                .retry(1L);

        mvc.perform(postWithAuth("/api/b/knowledge-documents/1/retry"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail.code").value("KNOWLEDGE_DOCUMENT_SEED_READONLY"));
    }

    @Test
    void archiveReturnsArchived() throws Exception {
        mvc.perform(postWithAuth("/api/b/knowledge-documents/2/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        verify(service).archive(2L);
    }

    /** 构造带 admin 鉴权属性的 multipart 请求。 */
    private MockMultipartHttpServletRequestBuilder multipartWithAuth(String url) {
        return (MockMultipartHttpServletRequestBuilder) multipart(url)
                .requestAttr(AuthFilter.ATTR_AUTH_SUBJECT, 1L)
                .requestAttr(AuthFilter.ATTR_AUTH_ROLE, "admin");
    }

    /** 构造带 admin 鉴权属性的 POST 请求。 */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postWithAuth(String url) {
        return post(url).requestAttr(AuthFilter.ATTR_AUTH_SUBJECT, 1L).requestAttr(AuthFilter.ATTR_AUTH_ROLE, "admin");
    }
}
