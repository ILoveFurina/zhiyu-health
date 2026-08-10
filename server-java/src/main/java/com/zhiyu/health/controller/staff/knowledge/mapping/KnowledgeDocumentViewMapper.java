package com.zhiyu.health.controller.staff.knowledge.mapping;

import com.zhiyu.health.entity.knowledge.KnowledgeDocument;
import com.zhiyu.health.service.knowledge.KnowledgeDocumentViews.DocumentView;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** KnowledgeDocument -> DocumentView：时间戳格式化为 ISO 字符串。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface KnowledgeDocumentViewMapper {

    @Mapping(target = "createdAt", source = "createdAt", qualifiedByName = "formatTimestamp")
    @Mapping(target = "updatedAt", source = "updatedAt", qualifiedByName = "formatTimestamp")
    DocumentView toView(KnowledgeDocument entity);

    @org.mapstruct.Named("formatTimestamp")
    default String formatTimestamp(OffsetDateTime timestamp) {
        return timestamp == null ? null : timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
