package com.zhiyu.health.controller.staff.knowledge.mapping;

import com.zhiyu.health.controller.staff.knowledge.GraphAdminController;
import com.zhiyu.health.service.knowledge.GraphNodeProps;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** 图谱节点输入 → GraphNodeProps：label 不属属性集，由 controller 单独传 service。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface GraphInputMapper {

    GraphNodeProps toProps(GraphAdminController.GraphNodeCreateInput input);

    GraphNodeProps toProps(GraphAdminController.GraphNodeUpdateInput input);
}
