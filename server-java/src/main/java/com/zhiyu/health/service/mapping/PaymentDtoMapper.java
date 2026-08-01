package com.zhiyu.health.service.mapping;

import com.zhiyu.health.entity.Payment;
import com.zhiyu.health.service.PaymentService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface PaymentDtoMapper {

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "appointmentId", source = "appointmentId")
    @Mapping(target = "amount", source = "amount")
    @Mapping(target = "status", source = "status")
    Payment toUnpaid(long appointmentId, BigDecimal amount, String status);

    @Mapping(target = "id", source = "payment.id")
    @Mapping(target = "appointmentId", source = "payment.appointmentId")
    @Mapping(target = "amount", source = "payment.amount")
    @Mapping(target = "status", source = "payment.status")
    @Mapping(target = "statusLabel", source = "statusLabel")
    @Mapping(target = "createdAt", source = "payment.createdAt", qualifiedByName = "offsetDateTimeText")
    @Mapping(target = "paidAt", source = "payment.paidAt", qualifiedByName = "offsetDateTimeText")
    @Mapping(target = "payable", source = "payable")
    PaymentService.PaymentView toView(Payment payment, String statusLabel, boolean payable);

    @Named("offsetDateTimeText")
    default String offsetDateTimeText(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}
