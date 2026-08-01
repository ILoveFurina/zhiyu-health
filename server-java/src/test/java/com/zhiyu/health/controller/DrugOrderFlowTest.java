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
import com.zhiyu.health.controller.b.DrugOrderAdminController;
import com.zhiyu.health.controller.c.DrugOrderController;
import com.zhiyu.health.controller.c.mapping.DrugOrderInputMapper;
import com.zhiyu.health.entity.DrugOrder;
import com.zhiyu.health.mapper.DrugOrderItemMapper;
import com.zhiyu.health.mapper.DrugOrderMapper;
import com.zhiyu.health.mapper.MedicationMapper;
import com.zhiyu.health.mapper.PrescriptionMapper;
import com.zhiyu.health.service.DrugOrderService;
import com.zhiyu.health.service.mapping.DrugOrderDtoMapper;
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

/** 药品订单跨端状态机 seam：同一订单由 C 端支付后交由 B 端确认完成。 */
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
    void sameOrderFlowsFromUnpaidThroughPatientPaymentToAdminCompletion() throws Exception {
        DrugOrder order = order();
        when(orderMapper.selectForPatientForUpdate(51L, 7L)).thenReturn(order);
        when(orderMapper.markPaid(51L, "PAID", "UNPAID")).thenReturn(1);
        when(orderMapper.selectForUpdate(51L)).thenReturn(order);
        when(orderMapper.complete(51L, "DONE", "PAID")).thenReturn(1);
        when(itemMapper.selectDetailed(51L)).thenReturn(List.of());

        mvc().perform(post("/api/c/drug-orders/51/pay").requestAttr("authSubject", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mvc().perform(post("/api/b/drug-orders/51/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
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
