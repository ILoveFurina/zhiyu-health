package com.zhiyu.health.controller.c;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.zhiyu.health.controller.c.mapping.DrugOrderInputMapper;
import com.zhiyu.health.entity.DrugOrder;
import com.zhiyu.health.entity.DrugOrderItem;
import com.zhiyu.health.entity.Medication;
import com.zhiyu.health.entity.Prescription;
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

class DrugOrderControllerTest {

    private final DrugOrderMapper orderMapper = mock(DrugOrderMapper.class);
    private final DrugOrderItemMapper itemMapper = mock(DrugOrderItemMapper.class);
    private final MedicationMapper medicationMapper = mock(MedicationMapper.class);
    private final PrescriptionMapper prescriptionMapper = mock(PrescriptionMapper.class);
    private final TransactionTemplate transactionTemplate = transactionTemplate();
    private final DrugOrderDtoMapper dtoMapper = Mappers.getMapper(DrugOrderDtoMapper.class);
    private final DrugOrderService service = new DrugOrderService(
            orderMapper,
            itemMapper,
            medicationMapper,
            prescriptionMapper,
            transactionTemplate,
            TestContracts.instance(),
            dtoMapper);
    private final DrugOrderController controller =
            new DrugOrderController(service, Mappers.getMapper(DrugOrderInputMapper.class));

    @Test
    void approvedPrescriptionCreatesUnpaidOrderAndDeductsStock() throws Exception {
        Prescription prescription = prescription("APPROVED");
        Medication medication = medication(1L, "阿莫西林胶囊", "18.50");
        when(prescriptionMapper.selectForPatient(31L, 7L)).thenReturn(prescription);
        when(medicationMapper.selectForPrescriptionForUpdate(31L)).thenReturn(List.of(medication));
        when(medicationMapper.deductStock(1L, 2)).thenReturn(1);
        doAnswer(invocation -> {
                    invocation.getArgument(0, DrugOrder.class).setId(51L);
                    return 1;
                })
                .when(orderMapper)
                .insert(any(DrugOrder.class));

        mvc().perform(
                        post("/api/c/drug-orders")
                                .requestAttr("authSubject", 7L)
                                .contentType("application/json")
                                .content(
                                        """
                                {"prescription_id":31,"items":[{"medication_id":1,"quantity":2}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(51))
                .andExpect(jsonPath("$.status").value("UNPAID"))
                .andExpect(jsonPath("$.total_amount").value(37.00));

        verify(medicationMapper).deductStock(1L, 2);
    }

    @Test
    void insufficientStockRejectsOrderWithoutCreatingIt() throws Exception {
        when(prescriptionMapper.selectForPatient(31L, 7L)).thenReturn(prescription("APPROVED"));
        when(medicationMapper.selectForPrescriptionForUpdate(31L))
                .thenReturn(List.of(medication(1L, "阿莫西林胶囊", "18.50")));
        when(medicationMapper.deductStock(1L, 2)).thenReturn(0);

        mvc().perform(
                        post("/api/c/drug-orders")
                                .requestAttr("authSubject", 7L)
                                .contentType("application/json")
                                .content(
                                        """
                                {"prescription_id":31,"items":[{"medication_id":1,"quantity":2}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("药品库存不足，下单失败"));

        verify(orderMapper, never()).insert(any(DrugOrder.class));
    }

    @Test
    void cancellingUnpaidOrderRestoresStock() throws Exception {
        DrugOrder order = new DrugOrder();
        order.setId(51L);
        order.setPatientId(7L);
        order.setPrescriptionId(31L);
        order.setStatus("UNPAID");
        order.setTotalAmount(new BigDecimal("37.00"));
        DrugOrderItem item = new DrugOrderItem();
        item.setDrugOrderId(51L);
        item.setMedicationId(1L);
        item.setMedicationName("阿莫西林胶囊");
        item.setSpecification("0.25g*24粒");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("18.50"));
        item.setSubtotal(new BigDecimal("37.00"));
        when(orderMapper.selectForPatientForUpdate(51L, 7L)).thenReturn(order);
        when(itemMapper.selectDetailed(51L)).thenReturn(List.of(item));
        when(medicationMapper.restoreStock(1L, 2)).thenReturn(1);
        when(orderMapper.cancel(51L, "CANCELLED", "UNPAID")).thenReturn(1);

        mvc().perform(post("/api/c/drug-orders/51/cancel").requestAttr("authSubject", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(medicationMapper).restoreStock(1L, 2);
    }

    @Test
    void listsOnlyCurrentPatientsOrders() throws Exception {
        DrugOrder order = new DrugOrder();
        order.setId(51L);
        order.setPatientId(7L);
        order.setPrescriptionId(31L);
        order.setStatus("UNPAID");
        order.setTotalAmount(new BigDecimal("18.50"));
        when(orderMapper.selectForPatient(7L)).thenReturn(List.of(order));
        when(itemMapper.selectDetailed(51L)).thenReturn(List.of());

        mvc().perform(get("/api/c/drug-orders").requestAttr("authSubject", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(51))
                .andExpect(jsonPath("$[0].status_label").value("待支付"));
    }

    private MockMvc mvc() {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private Prescription prescription(String status) {
        Prescription prescription = new Prescription();
        prescription.setId(31L);
        prescription.setStatus(status);
        return prescription;
    }

    private Medication medication(long id, String name, String price) {
        Medication medication = new Medication();
        medication.setId(id);
        medication.setName(name);
        medication.setSpecification("0.25g*24粒");
        medication.setPrice(new BigDecimal(price));
        return medication;
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> invocation
                .getArgument(0, TransactionCallback.class)
                .doInTransaction(mock(TransactionStatus.class)));
        return template;
    }
}
