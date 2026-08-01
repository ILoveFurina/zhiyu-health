package com.zhiyu.health.controller.b;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.entity.DrugOrder;
import com.zhiyu.health.entity.DrugOrderItem;
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

class DrugOrderAdminControllerTest {

    private final DrugOrderMapper orderMapper = mock(DrugOrderMapper.class);
    private final DrugOrderItemMapper itemMapper = mock(DrugOrderItemMapper.class);
    private final MedicationMapper medicationMapper = mock(MedicationMapper.class);
    private final PrescriptionMapper prescriptionMapper = mock(PrescriptionMapper.class);
    private final DrugOrderDtoMapper dtoMapper = Mappers.getMapper(DrugOrderDtoMapper.class);
    private final DrugOrderService service = new DrugOrderService(
            orderMapper,
            itemMapper,
            medicationMapper,
            prescriptionMapper,
            transactionTemplate(),
            TestContracts.instance(),
            dtoMapper);
    private final DrugOrderAdminController controller = new DrugOrderAdminController(service);

    @Test
    void filtersOrdersByContractStatus() throws Exception {
        DrugOrder order = order(51L, "PAID");
        when(orderMapper.selectForAdmin("PAID")).thenReturn(List.of(order));
        when(itemMapper.selectDetailed(51L)).thenReturn(List.of());

        mvc().perform(get("/api/b/drug-orders").param("status", "PAID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(51))
                .andExpect(jsonPath("$[0].status").value("PAID"))
                .andExpect(jsonPath("$[0].status_label").value("已支付"));
    }

    @Test
    void getsOrderDetail() throws Exception {
        DrugOrder order = order(51L, "UNPAID");
        when(orderMapper.selectById(51L)).thenReturn(order);
        when(itemMapper.selectDetailed(51L)).thenReturn(List.of());

        mvc().perform(get("/api/b/drug-orders/51"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(51))
                .andExpect(jsonPath("$.prescription_id").value(31))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void completingPaidOrderMarksItDone() throws Exception {
        when(orderMapper.selectForUpdate(51L)).thenReturn(order(51L, "PAID"));
        when(orderMapper.complete(51L, "DONE", "PAID")).thenReturn(1);
        when(itemMapper.selectDetailed(51L)).thenReturn(List.of());

        mvc().perform(post("/api/b/drug-orders/51/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.status_label").value("已完成"));
    }

    @Test
    void cancellingUnpaidOrderRestoresStock() throws Exception {
        DrugOrderItem item = new DrugOrderItem();
        item.setDrugOrderId(51L);
        item.setMedicationId(1L);
        item.setQuantity(2);
        when(orderMapper.selectForUpdate(51L)).thenReturn(order(51L, "UNPAID"));
        when(itemMapper.selectDetailed(51L)).thenReturn(List.of(item));
        when(medicationMapper.restoreStock(1L, 2)).thenReturn(1);
        when(orderMapper.cancel(51L, "CANCELLED", "UNPAID")).thenReturn(1);

        mvc().perform(post("/api/b/drug-orders/51/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(medicationMapper).restoreStock(1L, 2);
    }

    private DrugOrder order(long id, String status) {
        DrugOrder order = new DrugOrder();
        order.setId(id);
        order.setPatientId(7L);
        order.setPrescriptionId(31L);
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal("37.00"));
        return order;
    }

    private MockMvc mvc() {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation
                .getArgument(0, TransactionCallback.class)
                .doInTransaction(mock(TransactionStatus.class)));
        return template;
    }
}
