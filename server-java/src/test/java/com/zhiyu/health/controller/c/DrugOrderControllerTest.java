package com.zhiyu.health.controller.c;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.zhiyu.health.controller.patient.prescription.DrugOrderController;
import com.zhiyu.health.controller.patient.prescription.mapping.DrugOrderInputMapper;
import com.zhiyu.health.entity.prescription.DrugOrder;
import com.zhiyu.health.entity.prescription.DrugOrderItem;
import com.zhiyu.health.entity.prescription.Medication;
import com.zhiyu.health.entity.prescription.Prescription;
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
                .andExpect(jsonPath("$.source").value("PRESCRIPTION"))
                .andExpect(jsonPath("$.total_amount").value(37.00));

        verify(medicationMapper).deductStock(1L, 2);
    }

    @Test
    void onlineConsultationPrescriptionCreatesOrderLikeAppointmentOne() throws Exception {
        // 在线问诊处方（appointment_id 为空）与线下处方走同一下单主路径（票 56）。
        Prescription prescription = prescription("APPROVED");
        prescription.setOnlineConsultationId(41L);
        Medication medication = medication(1L, "阿莫西林胶囊", "18.50");
        when(prescriptionMapper.selectForPatient(32L, 7L)).thenReturn(prescription);
        when(medicationMapper.selectForPrescriptionForUpdate(32L)).thenReturn(List.of(medication));
        when(medicationMapper.deductStock(1L, 2)).thenReturn(1);
        doAnswer(invocation -> {
                    invocation.getArgument(0, DrugOrder.class).setId(52L);
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
                                {"prescription_id":32,"items":[{"medication_id":1,"quantity":2}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(52))
                .andExpect(jsonPath("$.status").value("UNPAID"));

        verify(medicationMapper).deductStock(1L, 2);
    }

    @Test
    void foreignPrescriptionIsInvisibleAndRejectedAs404() throws Exception {
        // 归属不符：selectForPatient 按患者过滤返回 null -> 404，不泄露存在性、不建单。
        when(prescriptionMapper.selectForPatient(31L, 7L)).thenReturn(null);

        mvc().perform(
                        post("/api/c/drug-orders")
                                .requestAttr("authSubject", 7L)
                                .contentType("application/json")
                                .content(
                                        """
                                {"prescription_id":31,"items":[{"medication_id":1,"quantity":2}]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("电子处方不存在"));

        verify(orderMapper, never()).insert(any(DrugOrder.class));
        verify(medicationMapper, never()).deductStock(anyLong(), anyInt());
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
    void repeatedMedicationLinesReusePrescriptionDetailsInsteadOfRejectingOrder() throws Exception {
        Medication firstLine = medication(1L, "阿莫西林胶囊", "18.50");
        Medication secondLine = medication(1L, "阿莫西林胶囊", "18.50");
        when(prescriptionMapper.selectForPatient(31L, 7L)).thenReturn(prescription("APPROVED"));
        when(medicationMapper.selectForPrescriptionForUpdate(31L)).thenReturn(List.of(firstLine, secondLine));
        when(medicationMapper.deductStock(1L, 1)).thenReturn(1);
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
                                {"prescription_id":31,"items":[
                                  {"medication_id":1,"quantity":1},
                                  {"medication_id":1,"quantity":2}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total_amount").value(55.50));
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
    void payingUnpaidOrderMarksItPaid() throws Exception {
        DrugOrder order = new DrugOrder();
        order.setId(51L);
        order.setPatientId(7L);
        order.setPrescriptionId(31L);
        order.setStatus("UNPAID");
        order.setTotalAmount(new BigDecimal("37.00"));
        when(orderMapper.selectForPatientForUpdate(51L, 7L)).thenReturn(order);
        when(orderMapper.markPaid(51L, "PAID", "UNPAID")).thenReturn(1);

        mvc().perform(post("/api/c/drug-orders/51/pay").requestAttr("authSubject", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.status_label").value("已支付"))
                .andExpect(jsonPath("$.cancellable").value(false));
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
                .andExpect(jsonPath("$[0].status_label").value("待支付"))
                .andExpect(jsonPath("$[0].cancellable").value(true));
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
        // 处方药路径不读此字段；OTC 路径校验它，默认 TRUE 与 seed 阿莫西林语义一致。
        medication.setIsPrescription(true);
        return medication;
    }

    // 票 76：OTC 下单（prescription_id 为空）走 selectByIdsForUpdate，药品须 is_prescription=FALSE。
    @Test
    void otcOrderWithoutPrescriptionCreatesUnpaidOrderAndDeductsStock() throws Exception {
        Medication otc = medication(2L, "布洛芬缓释胶囊", "22.00");
        otc.setIsPrescription(false);
        when(medicationMapper.selectByIdsForUpdate(List.of(2L))).thenReturn(List.of(otc));
        when(medicationMapper.deductStock(2L, 3)).thenReturn(1);
        doAnswer(invocation -> {
                    invocation.getArgument(0, DrugOrder.class).setId(61L);
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
                                {"items":[{"medication_id":2,"quantity":3}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(61))
                .andExpect(jsonPath("$.status").value("UNPAID"))
                .andExpect(jsonPath("$.source").value("OTC"))
                .andExpect(jsonPath("$.prescription_id").doesNotExist())
                .andExpect(jsonPath("$.total_amount").value(66.00));

        verify(medicationMapper).deductStock(2L, 3);
        verify(prescriptionMapper, never()).selectForPatient(anyLong(), anyLong());
    }

    @Test
    void otcOrderRejectsPrescriptionDrugWithoutApprovedPrescription() throws Exception {
        // 处方药（is_prescription=TRUE）不得走 OTC 路径，须凭已审核处方（ADR-0032 硬约束）。
        Medication rx = medication(1L, "阿莫西林胶囊", "18.50");
        when(medicationMapper.selectByIdsForUpdate(List.of(1L))).thenReturn(List.of(rx));

        mvc().perform(
                        post("/api/c/drug-orders")
                                .requestAttr("authSubject", 7L)
                                .contentType("application/json")
                                .content(
                                        """
                                {"items":[{"medication_id":1,"quantity":2}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("处方药须凭已审核电子处方购买"));

        verify(orderMapper, never()).insert(any(DrugOrder.class));
        verify(medicationMapper, never()).deductStock(anyLong(), anyInt());
    }

    @Test
    void otcOrderWithoutItemsIsRejected() throws Exception {
        mvc().perform(post("/api/c/drug-orders")
                        .requestAttr("authSubject", 7L)
                        .contentType("application/json")
                        .content("""
                                {}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("OTC 下单必须指定药品与数量"));

        verify(orderMapper, never()).insert(any(DrugOrder.class));
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> invocation
                .getArgument(0, TransactionCallback.class)
                .doInTransaction(mock(TransactionStatus.class)));
        return template;
    }
}
