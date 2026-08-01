package com.zhiyu.health.service.mapping;

import com.zhiyu.health.entity.Payment;
import java.math.BigDecimal;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PaymentDtoMapper {

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "appointmentId", source = "appointmentId")
    @Mapping(target = "amount", source = "amount")
    @Mapping(target = "status", source = "status")
    Payment toUnpaid(long appointmentId, BigDecimal amount, String status);
}
