package com.zhiyu.health.service.mapping;

import com.zhiyu.health.entity.PreconsultationDraft;
import com.zhiyu.health.service.PreconsultationService;
import java.time.OffsetDateTime;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PreconsultationDtoMapper {

    @Mapping(target = "id", source = "draft.id")
    @Mapping(target = "status", source = "draft.status")
    @Mapping(target = "conversationId", source = "draft.conversationId")
    @Mapping(target = "healthProfileId", source = "draft.healthProfileId")
    @Mapping(target = "createdAt", source = "draft.createdAt")
    @Mapping(target = "statusLabel", source = "statusLabel")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "currentConsultationId", source = "currentConsultationId")
    PreconsultationService.DraftView toView(
            PreconsultationDraft draft,
            String statusLabel,
            PreconsultationService.DraftSummaryView summary,
            Long currentConsultationId);

    @Mapping(target = "disclaimer", source = "draft.summaryDisclaimer")
    @Mapping(target = "updatedAt", source = "draft.summaryUpdatedAt")
    @Mapping(target = "suggestedStandardDepartmentName", source = "suggestedStandardDepartmentName")
    PreconsultationService.DraftSummaryView toSummaryView(
            PreconsultationDraft draft, String suggestedStandardDepartmentName);

    default String map(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}
