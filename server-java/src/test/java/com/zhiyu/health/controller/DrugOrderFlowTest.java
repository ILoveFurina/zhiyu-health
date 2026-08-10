package com.zhiyu.health.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.controller.patient.prescription.DrugOrderController;
import com.zhiyu.health.controller.patient.prescription.mapping.DrugOrderInputMapper;
import com.zhiyu.health.controller.staff.prescription.DrugOrderAdminController;
import com.zhiyu.health.entity.prescription.DrugOrder;
import com.zhiyu.health.mapper.prescription.DrugOrderItemMapper;
import com.zhiyu.health.mapper.prescription.DrugOrderMapper;
import com.zhiyu.health.mapper.prescription.MedicationMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import com.zhiyu.health.service.prescription.DrugOrderService;
import com.zhiyu.health.service.prescription.mapping.DrugOrderDtoMapper;
import com.zhiyu.health.support.TestContracts;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 药品订单 C 端支付 seam：患者对待支付订单完成模拟支付（UNPAID -> PAID）。
 * 票 88（ADR-0035）：B 端「确认完成」（PAID -> DONE）随 DONE 状态移除，
 * 履约推进（配送/自取两条前向状态机）属票 88 阶段二。 */
class DrugOrderFlowTest {

    private final DrugOrderMapper orderMapper = mock(DrugOrderMapper.class);
    private final DrugOrderItemMapper itemMapper = mock(DrugOrderItemMapper.class);
    private final DrugOrderService service = new DrugOrderService(
            orderMapper,
            itemMapper,
            mock(MedicationMapper.class),
            mock(PrescriptionMapper.class),
            transactionTemplate(),
            TestContracts.instance(),
            Mappers.getMapper(DrugOrderDtoMapper.class));

    @Test
    void unpaidOrderFlowsToPaidAfterPatientPayment() throws Exception {
        DrugOrder order = order();
        when(orderMapper.selectForPatientForUpdate(51L, 7L)).thenReturn(order);
        when(orderMapper.markPaid(51L, "PAID", "UNPAID")).thenReturn(1);
        when(itemMapper.selectDetailed(51L)).thenReturn(List.of());

        mvc().perform(post("/api/c/drug-orders/51/pay").requestAttr("authSubject", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    private DrugOrder order() {
        DrugOrder order = new DrugOrder();
        order.setId(51L);
        order.setPatientId(7L);
        order.setPrescriptionId(31L);
        order.setStatus("UNPAID");
        order.setTotalAmount(new BigDecimal("37.00"));
        return order;
    }

    private MockMvc mvc() {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        DrugOrderController patientController =
                new DrugOrderController(service, Mappers.getMapper(DrugOrderInputMapper.class));
        return standaloneSetup(patientController, new DrugOrderAdminController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> invocation
                .getArgument(0, TransactionCallback.class)
                .doInTransaction(mock(TransactionStatus.class)));
        return template;
    }
}
