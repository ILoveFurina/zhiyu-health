package com.zhiyu.health.service.appointment.mapping;

import com.zhiyu.health.entity.appointment.Appointment;
import com.zhiyu.health.entity.scheduling.TimeSlot;
import com.zhiyu.health.service.appointment.AppointmentService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * 挂号单对象映射器：将 {@link Appointment} 实体转换为 {@link AppointmentService.AppointmentView}。
 * <p>
 * 负责字段映射与格式转换（日期/时间/枚举 → 文本），由 MapStruct 编译期生成实现。
 * 视图字段中的状态码、状态标签、支付状态标签、可取消性由调用方（{@link AppointmentService}）计算后传入，
 * 本 mapper 只做纯数据转换，不做业务判断。
 * </p>
 */
@Mapper(componentModel = "spring")
public interface AppointmentDtoMapper {

    /**
     * 将 Appointment 实体装配为 service 层视图。
     *
     * @param appointment       挂号单实体（含联查投影字段）
     * @param statusCode        挂号状态编码（契约值）
     * @param statusLabel       挂号状态显示文本
     * @param paymentStatusLabel 支付状态显示文本
     * @param cancellable       是否可取消
     * @return service 层视图
     */
    @Mapping(target = "id", source = "appointment.id")
    @Mapping(target = "scheduleId", source = "appointment.scheduleId")
    @Mapping(target = "doctorId", source = "appointment.doctorId")
    @Mapping(target = "doctorName", source = "appointment.doctorName")
    @Mapping(target = "departmentName", source = "appointment.departmentName")
    @Mapping(target = "scheduleDate", source = "appointment.scheduleDate", qualifiedByName = "dateText")
    @Mapping(target = "timeSlot", source = "appointment.timeSlot", qualifiedByName = "timeSlotText")
    @Mapping(target = "sequenceNumber", source = "appointment.sequenceNumber")
    @Mapping(target = "statusCode", source = "statusCode")
    @Mapping(target = "status", source = "statusLabel")
    @Mapping(target = "registrationFee", source = "appointment.registrationFee")
    @Mapping(target = "paymentStatus", source = "appointment.paymentStatus")
    @Mapping(target = "paymentStatusLabel", source = "paymentStatusLabel")
    @Mapping(target = "conditionSummary", source = "appointment.conditionSummary")
    @Mapping(target = "hospitalName", source = "appointment.hospitalName")
    @Mapping(target = "campusName", source = "appointment.campusName")
    @Mapping(target = "campusAddress", source = "appointment.campusAddress")
    @Mapping(target = "createdAt", source = "appointment.createdAt", qualifiedByName = "dateTimeText")
    @Mapping(target = "paymentDeadline", source = "appointment.paymentDeadline", qualifiedByName = "dateTimeText")
    @Mapping(target = "cancellable", source = "cancellable")
    AppointmentService.AppointmentView toView(
            Appointment appointment,
            String statusCode,
            String statusLabel,
            String paymentStatusLabel,
            boolean cancellable);

    /**
     * 日期格式化：{@link LocalDate} → ISO-8601 字符串（yyyy-MM-dd）。
     *
     * @param value 日期
     * @return 格式化字符串，null 时返回 null
     */
    @Named("dateText")
    default String dateText(LocalDate value) {
        return value == null ? null : value.toString();
    }

    /**
     * 时段格式化：{@link TimeSlot} 枚举 → 显示文本。
     *
     * @param value 时段枚举
     * @return 时段文本值，null 时返回 null
     */
    @Named("timeSlotText")
    default String timeSlotText(TimeSlot value) {
        return value == null ? null : value.getValue();
    }

    @Named("dateTimeText")
    default String dateTimeText(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}
