package com.zhiyu.health.controller.patient.appointment.mapping;

import com.zhiyu.health.controller.agent.AppointmentToolController;
import com.zhiyu.health.controller.patient.appointment.AppointmentCardBase;
import com.zhiyu.health.controller.patient.appointment.AppointmentController;
import com.zhiyu.health.service.appointment.AppointmentService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 挂号卡片对象映射器：将 {@link AppointmentService.AppointmentView} 转换为不同场景下的输出格式。
 * <p>
 * 职责：
 * <ul>
 *   <li>{@link #toBase} —— 提取公共字段，供 C 端与 Agent 两端复用</li>
 *   <li>{@link #toPatientOut} —— 装配 C 端小程序完整卡片（含支付状态、可取消性等 C 端专属字段）</li>
 *   <li>{@link #toAgentCard} —— 装配 Agent 对话卡片（含摘要发送标记、就诊通知等 Agent 专属字段）</li>
 * </ul>
 * 所有映射由 MapStruct 编译期生成实现，避免手写样板代码。
 * </p>
 */
@Mapper(componentModel = "spring")
public interface AppointmentCardMapper {

    /**
     * 提取挂号卡片的公共字段基座。
     *
     * @param value             service 层输出的挂号视图
     * @param summaryDisclaimer 病情摘要免责声明（由 {@link com.zhiyu.health.service.common.DisclaimerService} 注入）
     * @return 公共字段基座
     */
    @Mapping(target = "appointmentId", source = "value.id")
    @Mapping(target = "summaryDisclaimer", source = "summaryDisclaimer")
    AppointmentCardBase toBase(AppointmentService.AppointmentView value, String summaryDisclaimer);

    /**
     * 装配 C 端小程序挂号卡片输出。
     *
     * @param value             service 层输出的挂号视图
     * @param summaryDisclaimer 病情摘要免责声明
     * @param paymentPayable    是否允许发起支付（待支付且未超时）
     * @param cancellable       是否可取消（由状态机与取消时间窗口计算）
     * @return C 端卡片 DTO
     */
    @Mapping(target = "appointmentId", source = "value.id")
    @Mapping(target = "summaryDisclaimer", source = "summaryDisclaimer")
    @Mapping(target = "createdAt", source = "value.createdAt")
    @Mapping(target = "paymentDeadline", source = "value.paymentDeadline")
    AppointmentController.AppointmentOut toPatientOut(
            AppointmentService.AppointmentView value,
            String summaryDisclaimer,
            boolean paymentPayable,
            boolean cancellable);

    /**
     * 装配 Agent 对话中的挂号卡片。
     *
     * @param base        公共字段基座
     * @param summarySent 病情摘要是否已发送给患者
     * @param notice      就诊通知文案
     * @return Agent 卡片 DTO
     */
    @Mapping(target = "summarySent", source = "summarySent")
    @Mapping(target = "notice", source = "notice")
    AppointmentToolController.AppointmentCard toAgentCard(AppointmentCardBase base, boolean summarySent, String notice);
}
