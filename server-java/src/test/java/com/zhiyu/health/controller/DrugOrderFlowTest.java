package com.zhiyu.health.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import com.zhiyu.health.entity.pharmacy.CampusPharmacy;
import com.zhiyu.health.entity.pharmacy.PharmacyMedication;
import com.zhiyu.health.entity.pharmacy.PharmacyProfile;
import com.zhiyu.health.entity.prescription.DrugOrder;
import com.zhiyu.health.entity.prescription.DrugOrderFulfillmentEvent;
import com.zhiyu.health.entity.prescription.Prescription;
import com.zhiyu.health.entity.prescription.PrescriptionItem;
import com.zhiyu.health.mapper.pharmacy.CampusPharmacyMapper;
import com.zhiyu.health.mapper.pharmacy.PharmacyMedicationMapper;
import com.zhiyu.health.mapper.prescription.DrugOrderFulfillmentEventMapper;
import com.zhiyu.health.mapper.prescription.DrugOrderItemMapper;
import com.zhiyu.health.mapper.prescription.DrugOrderMapper;
import com.zhiyu.health.mapper.prescription.MedicationMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionItemMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import com.zhiyu.health.service.prescription.DrugOrderService;
import com.zhiyu.health.service.prescription.MedCheckinService;
import com.zhiyu.health.support.TestContracts;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 药品订单主链路连贯流程（票 88，ADR-0035）：处方药下单 -> 支付核销 ->
 * B 端配送履约（DISPENSE -> SHIP -> DELIVER）-> 交付触发用药提醒生成。
 * 真实 service + mock mapper，验证跨端状态推进的时序与快照。
 */
class DrugOrderFlowTest {

    private final DrugOrderMapper orderMapper = mock(DrugOrderMapper.class);
    private final DrugOrderItemMapper itemMapper = mock(DrugOrderItemMapper.class);
    private final DrugOrderFulfillmentEventMapper eventMapper = mock(DrugOrderFulfillmentEventMapper.class);
    private final PrescriptionMapper prescriptionMapper = mock(PrescriptionMapper.class);
    private final PrescriptionItemMapper prescriptionItemMapper = mock(PrescriptionItemMapper.class);
    private final PharmacyMedicationMapper pharmacyMedicationMapper = mock(PharmacyMedicationMapper.class);
    private final com.zhiyu.health.service.pharmacy.CampusPharmacyService campusPharmacyService =
            mock(com.zhiyu.health.service.pharmacy.CampusPharmacyService.class);
    private final CampusPharmacyMapper campusPharmacyMapper = mock(CampusPharmacyMapper.class);
    private final MedCheckinService medCheckinService = mock(MedCheckinService.class);
    private final DrugOrderService service = new DrugOrderService(
            orderMapper,
            itemMapper,
            eventMapper,
            prescriptionMapper,
            prescriptionItemMapper,
            pharmacyMedicationMapper,
            mock(MedicationMapper.class),
            campusPharmacyService,
            campusPharmacyMapper,
            transactionTemplate(),
            TestContracts.instance(),
            medCheckinService);

    @Test
    void prescriptionOrderFlowsThroughPaymentAndDeliveryFulfillment() throws Exception {
        // 下单：处方锁来源院区药房，复用处方数量 2，库存原子预扣
        Prescription prescription = new Prescription();
        prescription.setId(31L);
        prescription.setStatus("APPROVED");
        prescription.setSourceCampusId(11L);
        when(prescriptionMapper.selectForPatient(31L, 7L)).thenReturn(prescription);
        CampusPharmacy pharmacy = new CampusPharmacy();
        pharmacy.setId(71L);
        pharmacy.setCampusId(11L);
        pharmacy.setDisplayName("主院区大药房");
        pharmacy.setDeliveryFee(new BigDecimal("5.00"));
        pharmacy.setEstimatedDeliveryMinutes(45);
        when(campusPharmacyService.requireByCampusId(11L)).thenReturn(pharmacy);
        PharmacyProfile profile = new PharmacyProfile();
        profile.setPharmacyId(71L);
        profile.setDisplayName("主院区大药房");
        profile.setHospitalName("云澜医院");
        profile.setCampusName("主院区");
        profile.setCampusAddress("澜山市城东区梧桐路1号");
        when(campusPharmacyMapper.selectProfileById(71L)).thenReturn(profile);
        PrescriptionItem prescriptionItem = new PrescriptionItem();
        prescriptionItem.setMedicationId(1L);
        prescriptionItem.setMedicationName("阿莫西林胶囊");
        prescriptionItem.setSpecification("0.25g*24粒");
        prescriptionItem.setQuantity(2);
        when(prescriptionItemMapper.selectDetailed(31L)).thenReturn(List.of(prescriptionItem));
        PharmacyMedication pm = new PharmacyMedication();
        pm.setId(101L);
        pm.setPharmacyId(71L);
        pm.setMedicationId(1L);
        pm.setPrice(new BigDecimal("18.50"));
        pm.setStock(10);
        pm.setIsOnSale(true);
        pm.setMedicationName("阿莫西林胶囊");
        pm.setSpecification("0.25g*24粒");
        when(pharmacyMedicationMapper.selectForUpdateByPharmacyAndMedicationIds(71L, List.of(1L)))
                .thenReturn(List.of(pm));
        when(pharmacyMedicationMapper.deductStock(101L, 2)).thenReturn(1);
        doAnswer(invocation -> {
                    DrugOrder inserted = invocation.getArgument(0, DrugOrder.class);
                    inserted.setId(51L);
                    inserted.setCreatedAt(OffsetDateTime.now());
                    return 1;
                })
                .when(orderMapper)
                .insert(any(DrugOrder.class));
        when(orderMapper.selectById(51L)).thenAnswer(invocation -> {
            DrugOrder saved = persisted("UNPAID");
            return saved;
        });

        MockMvc mvc = mvc();
        mvc.perform(
                        post("/api/c/drug-orders")
                                .requestAttr("authSubject", 7L)
                                .contentType("application/json")
                                .content(
                                        """
                                {"prescription_id":31,"pickup_method":"DELIVERY",
                                 "receiver_name":"张三","receiver_phone":"13812345678","receiver_address":"澜山市城东区梧桐路12号"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNPAID"))
                .andExpect(jsonPath("$.pharmacy.display_name").value("主院区大药房"))
                .andExpect(jsonPath("$.total_amount").value(42.00));
        verify(pharmacyMedicationMapper).deductStock(101L, 2);

        // 支付：UNPAID -> PAID，处方一次性核销同事务
        when(orderMapper.selectForPatientForUpdate(51L, 7L)).thenReturn(persisted("UNPAID"));
        when(orderMapper.markPaid(51L, "PAID", "UNPAID")).thenReturn(1);
        when(prescriptionMapper.redeem(31L, 51L)).thenReturn(1);
        mvc.perform(post("/api/c/drug-orders/51/pay").requestAttr("authSubject", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        verify(prescriptionMapper).redeem(31L, 51L);

        // B 端履约：DISPENSE -> SHIP（生成虚构承运方/单号）-> DELIVER（触发提醒）
        when(orderMapper.selectForUpdate(51L)).thenReturn(persisted("PAID"));
        when(orderMapper.markDispensing(51L, "DISPENSING", "PAID")).thenReturn(1);
        when(orderMapper.selectDetailedForAdmin(51L)).thenReturn(persisted("DISPENSING"));
        mvc.perform(post("/api/b/drug-orders/51/fulfillment")
                        .requestAttr("authSubject", 9L)
                        .contentType("application/json")
                        .content("{\"decision\":\"DISPENSE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPENSING"));

        when(orderMapper.selectForUpdate(51L)).thenReturn(persisted("DISPENSING"));
        when(orderMapper.markShipped(
                        eq(51L), eq("SHIPPED"), eq("DISPENSING"), eq("DELIVERY"), eq("智愈模拟配送"), eq("ZY0000000051")))
                .thenReturn(1);
        DrugOrder shipped = persisted("SHIPPED");
        shipped.setCarrierName("智愈模拟配送");
        shipped.setTrackingNo("ZY0000000051");
        when(orderMapper.selectDetailedForAdmin(51L)).thenReturn(shipped);
        mvc.perform(post("/api/b/drug-orders/51/fulfillment")
                        .requestAttr("authSubject", 9L)
                        .contentType("application/json")
                        .content("{\"decision\":\"SHIP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carrier_name").value("智愈模拟配送"))
                .andExpect(jsonPath("$.tracking_no").value("ZY0000000051"));

        when(orderMapper.selectForUpdate(51L)).thenReturn(persisted("SHIPPED"));
        when(orderMapper.markDelivered(51L, "DELIVERED", "SHIPPED")).thenReturn(1);
        when(orderMapper.selectDetailedForAdmin(51L)).thenReturn(persisted("DELIVERED"));
        mvc.perform(post("/api/b/drug-orders/51/fulfillment")
                        .requestAttr("authSubject", 9L)
                        .contentType("application/json")
                        .content("{\"decision\":\"DELIVER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        // 处方药订单到达交付终态：用药提醒生成被触发一次；每次推进都落 append-only 事件
        ArgumentCaptor<DrugOrder> orderCaptor = ArgumentCaptor.forClass(DrugOrder.class);
        verify(medCheckinService).generateForDeliveredOrder(orderCaptor.capture());
        org.mockito.Mockito.verify(eventMapper, org.mockito.Mockito.times(3))
                .insert(any(DrugOrderFulfillmentEvent.class));
    }

    private DrugOrder persisted(String status) {
        DrugOrder order = new DrugOrder();
        order.setId(51L);
        order.setPatientId(7L);
        order.setPrescriptionId(31L);
        order.setPharmacyId(71L);
        order.setPickupMethod("DELIVERY");
        order.setStatus(status);
        order.setMedicationAmount(new BigDecimal("37.00"));
        order.setDeliveryFee(new BigDecimal("5.00"));
        order.setTotalAmount(new BigDecimal("42.00"));
        order.setPharmacyName("主院区大药房");
        order.setHospitalName("云澜医院");
        order.setCampusName("主院区");
        order.setCampusAddress("澜山市城东区梧桐路1号");
        order.setReceiverName("张三");
        order.setReceiverPhone("13812345678");
        order.setReceiverAddress("澜山市城东区梧桐路12号");
        order.setPaymentDeadline(OffsetDateTime.now().plusSeconds(600));
        order.setCreatedAt(OffsetDateTime.now());
        return order;
    }

    private MockMvc mvc() {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return standaloneSetup(
                        new DrugOrderController(service, Mappers.getMapper(DrugOrderInputMapper.class)),
                        new DrugOrderAdminController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private static TransactionTemplate transactionTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> invocation
                .getArgument(0, TransactionCallback.class)
                .doInTransaction(mock(TransactionStatus.class)));
        return template;
    }
}
