package com.zhiyu.health.service.appointment.mapping;

import com.zhiyu.health.entity.appointment.Payment;
import com.zhiyu.health.service.appointment.PaymentService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * 收费单对象映射器：Payment 实体 ↔ DTO 转换。
 * <p>
 * 提供两个核心方法：
 * <ul>
 *   <li>{@link #toUnpaid} —— 组装待支付收费单实体（INSERT 用）</li>
 *   <li>{@link #toView} —— 将收费实体转换为视图 DTO（查询用）</li>
 * </ul>
 * 由 MapStruct 编译期生成实现。
 * </p>
 */
@Mapper(componentModel = "spring")
public interface PaymentDtoMapper {

    /**
     * 组装待支付收费单实体。
     * <p>
     * 使用 {@code ignoreByDefault = true} 只映射显式指定的字段，避免未初始化的字段被意外写入。
     * </p>
     *
     * @param appointmentId 关联挂号单 ID
     * @param amount        挂号费金额
     * @param status        初始状态（unpaid）
     * @return 待支付收费单实体
     */
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "appointmentId", source = "appointmentId")
    @Mapping(target = "amount", source = "amount")
    @Mapping(target = "status", source = "status")
    Payment toUnpaid(long appointmentId, BigDecimal amount, String status);

    /**
     * 将收费实体转换为视图 DTO。
     *
     * @param payment     收费实体
     * @param statusLabel 状态显示文本
     * @param payable     是否允许支付
     * @return 收费视图
     */
    @Mapping(target = "id", source = "payment.id")
    @Mapping(target = "appointmentId", source = "payment.appointmentId")
    @Mapping(target = "amount", source = "payment.amount")
    @Mapping(target = "status", source = "payment.status")
    @Mapping(target = "statusLabel", source = "statusLabel")
    @Mapping(target = "createdAt", source = "payment.createdAt", qualifiedByName = "offsetDateTimeText")
    @Mapping(target = "paidAt", source = "payment.paidAt", qualifiedByName = "offsetDateTimeText")
    @Mapping(target = "refundedAt", source = "payment.refundedAt", qualifiedByName = "offsetDateTimeText")
    @Mapping(target = "payable", source = "payable")
    PaymentService.PaymentView toView(Payment payment, String statusLabel, boolean payable);

    /**
     * 时间戳格式化：{@link OffsetDateTime} → ISO-8601 字符串。
     *
     * @param value 时间戳
     * @return 格式化字符串，null 时返回 null
     */
    @Named("offsetDateTimeText")
    default String offsetDateTimeText(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}
