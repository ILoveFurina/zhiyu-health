package com.zhiyu.health.service.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhiyu.health.entity.ReportInterpretation;
import com.zhiyu.health.service.ReportInterpretationService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ReportInterpretationDtoMapper {

    @Mapping(target = "reportInterpretationId", source = "record.id")
    @Mapping(target = "conversationId", source = "record.conversationId")
    @Mapping(target = "status", source = "record.status")
    @Mapping(target = "pageCount", source = "record.pageCount")
    @Mapping(target = "result", source = "result")
    @Mapping(target = "disclaimer", source = "disclaimer")
    @Mapping(target = "profileName", source = "profileName")
    ReportInterpretationService.ReportView toView(
            ReportInterpretation record, JsonNode result, String disclaimer, String profileName);
}
