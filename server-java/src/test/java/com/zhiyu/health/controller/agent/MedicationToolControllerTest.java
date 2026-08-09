package com.zhiyu.health.controller.agent;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.service.MedicationToolService;
import com.zhiyu.health.service.MedicationToolService.MedicationView;
import com.zhiyu.health.service.MedicationToolService.PrepareLineView;
import com.zhiyu.health.service.MedicationToolService.PrepareOrderView;
import com.zhiyu.health.service.MedicationToolService.PrescriptionCardView;
import com.zhiyu.health.service.MedicationToolService.PrescriptionItemView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 购药工具回调接口单测（票 75）：验证 controller 只做参数校验与装配、委托 service，
 * 并覆盖处方药不得走 OTC 路径、prepare 不扣库存等约束。
 */
class MedicationToolControllerTest {

    private final MedicationToolService service = mock(MedicationToolService.class);

    @Test
    void searchMedicationsDelegatesNameToService() throws Exception {
        when(service.searchOtc("布洛芬")).thenReturn(List.of(otcMedication()));

        mvc().perform(get("/api/agent/medications").param("name", "布洛芬"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.medications[0].medication_id").value(2))
                .andExpect(jsonPath("$.medications[0].name").value("布洛芬缓释胶囊"))
                .andExpect(jsonPath("$.medications[0].is_active").value(true));
        verify(service).searchOtc("布洛芬");
    }

    @Test
    void listApprovedPrescriptionsPassesPatientIdAndReturnsItems() throws Exception {
        when(service.listApprovedPrescriptions(12L)).thenReturn(List.of(approvedPrescription()));

        mvc().perform(get("/api/agent/prescriptions").param("patient_id", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prescriptions[0].prescription_id").value(5))
                .andExpect(jsonPath("$.prescriptions[0].doctor_name").value("周安宁"))
                .andExpect(jsonPath("$.prescriptions[0].source_type").value("APPOINTMENT"))
                .andExpect(jsonPath("$.prescriptions[0].items[0].name").value("阿莫西林胶囊"))
                .andExpect(jsonPath("$.prescriptions[0].items[0].dosage").value("每次1粒"));
        verify(service).listApprovedPrescriptions(12L);
    }

    @Test
    void prepareOtcAssemblesCardWithoutDeductingStock() throws Exception {
        when(service.prepare(eq(2L), eq(3), eq(null), eq(12L))).thenReturn(otcPrepareCard());

        mvc().perform(get("/api/agent/drug-orders/prepare")
                        .param("patient_id", "12")
                        .param("medication_id", "2")
                        .param("quantity", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("OTC"))
                .andExpect(jsonPath("$.items[0].medication_id").value(2))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].unit_price").value(22.00))
                .andExpect(jsonPath("$.items[0].subtotal").value(66.00))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.total_amount").value(66.00));
        verify(service).prepare(2L, 3, null, 12L);
    }

    @Test
    void preparePrescriptionDrugReturnsPrescriptionSource() throws Exception {
        when(service.prepare(eq(null), eq(null), eq(5L), eq(12L))).thenReturn(prescriptionPrepareCard());

        mvc().perform(get("/api/agent/drug-orders/prepare")
                        .param("patient_id", "12")
                        .param("prescription_id", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PRESCRIPTION"))
                .andExpect(jsonPath("$.prescription_id").value(5))
                .andExpect(jsonPath("$.prescription_source_type").value("APPOINTMENT"))
                .andExpect(jsonPath("$.doctor_name").value("周安宁"));
        verify(service).prepare(null, null, 5L, 12L);
    }

    @Test
    void prepareOtcRejectsPrescriptionDrug() throws Exception {
        // ADR-0032 硬约束：处方药不得走 OTC 路径，service 抛 409，controller 零 try-catch 经 advice 出口。
        when(service.prepare(1L, 2, null, 12L)).thenThrow(new ApiException(409, "处方药须凭已审核电子处方购买"));

        mvc().perform(get("/api/agent/drug-orders/prepare")
                        .param("patient_id", "12")
                        .param("medication_id", "1")
                        .param("quantity", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("处方药须凭已审核电子处方购买"));
    }

    // standaloneSetup 不加载 application.yml 的全局 SNAKE_CASE 策略，须显式注入消息转换器
    // （与 DrugOrderFlowTest 同一处理方式），否则 record camelCase 字段会序列化成 medicationId。
    private MockMvc mvc() {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return standaloneSetup(new MedicationToolController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private MedicationView otcMedication() {
        return new MedicationView(2L, "布洛芬缓释胶囊", "布洛芬", "0.3g*20粒", new BigDecimal("22.00"), 280, true);
    }

    private PrescriptionCardView approvedPrescription() {
        return new PrescriptionCardView(
                5L,
                "周安宁",
                "APPOINTMENT",
                "线下接诊",
                "2026-07-29",
                List.of(new PrescriptionItemView(1L, "阿莫西林胶囊", "0.25g*24粒", "每次1粒", "每日三次", "7天")));
    }

    private PrepareOrderView otcPrepareCard() {
        PrepareLineView line = new PrepareLineView(
                2L, "布洛芬缓释胶囊", "0.3g*20粒", 3, new BigDecimal("22.00"), new BigDecimal("66.00"), 280, true);
        return new PrepareOrderView(
                "OTC", null, List.of(line), new BigDecimal("66.00"), new BigDecimal("66.00"), null, null, null);
    }

    private PrepareOrderView prescriptionPrepareCard() {
        PrepareLineView line = new PrepareLineView(
                1L, "阿莫西林胶囊", "0.25g*24粒", 1, new BigDecimal("18.50"), new BigDecimal("18.50"), 320, true);
        return new PrepareOrderView(
                "PRESCRIPTION",
                5L,
                List.of(line),
                new BigDecimal("18.50"),
                new BigDecimal("18.50"),
                "APPOINTMENT",
                "周安宁",
                "2026-07-29");
    }
}
