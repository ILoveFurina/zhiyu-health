package com.zhiyu.health.controller.staff.knowledge;

import com.zhiyu.health.config.AuthFilter;
import com.zhiyu.health.controller.staff.knowledge.mapping.KnowledgeDocumentViewMapper;
import com.zhiyu.health.entity.knowledge.KnowledgeDocument;
import com.zhiyu.health.service.knowledge.KnowledgeDocumentService;
import com.zhiyu.health.service.knowledge.KnowledgeDocumentViews.DocumentView;
import com.zhiyu.health.service.knowledge.KnowledgeDocumentViews.UploadResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识文档管理 B 端接口（票 89，ADR-0036）。
 *
 * <p>admin 鉴权由 AdminInterceptor 自动拦截（路径前缀 /api/b/**）。
 * controller 只校验装配，零业务逻辑，异常抛 ApiException。
 */
@RestController
@RequestMapping("/api/b/knowledge-documents")
@RequiredArgsConstructor
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeDocumentViewMapper viewMapper;

    /** 上传文档：multipart 上传，校验 type/size 读契约，立即返回 PROCESSING。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("department") String department,
            @RequestAttribute(AuthFilter.ATTR_AUTH_SUBJECT) Long staffId) {
        if (department == null || department.isBlank()) {
            throw new com.zhiyu.health.config.ApiException(400, "请选择标准科室");
        }
        KnowledgeDocument doc = knowledgeDocumentService.upload(file, department.trim(), staffId);
        return new UploadResponse(doc.getId(), doc.getStatus());
    }

    /** 列表：含 seed + upload，返回 status/chunk_count/source。 */
    @GetMapping
    public List<DocumentView> list() {
        return knowledgeDocumentService.listAll().stream()
                .map(viewMapper::toView)
                .toList();
    }

    /** 重试：先删已写 chunk 再整链重跑（幂等）。 */
    @PostMapping("/{id}/retry")
    public UploadResponse retry(@PathVariable long id) {
        knowledgeDocumentService.retry(id);
        return new UploadResponse(id, "PROCESSING");
    }

    /** 归档：物理删 chunk + 文档 status=ARCHIVED（元数据 + MinIO 原文保留）。 */
    @PostMapping("/{id}/archive")
    public UploadResponse archive(@PathVariable long id) {
        knowledgeDocumentService.archive(id);
        return new UploadResponse(id, "ARCHIVED");
    }
}
