package com.zhiyu.health.controller.agent.mapping;

import com.zhiyu.health.controller.agent.ContraindicationController;
import com.zhiyu.health.rule.ContraindicationResult;
import com.zhiyu.health.service.ContraindicationService;
import org.mapstruct.Mapper;

/** Agent 禁忌回调的跨栈 DTO 映射；字段形状由 MapStruct 编译期校验。 */
@Mapper(componentModel = "spring")
public interface ContraindicationDtoMapper {
    ContraindicationService.CheckCommand toCommand(ContraindicationController.CheckRequest request);

    ContraindicationController.CheckResponse toResponse(ContraindicationResult result);
}
