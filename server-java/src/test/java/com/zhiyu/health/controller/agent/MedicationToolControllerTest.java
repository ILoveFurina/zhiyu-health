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
import com.zhiyu.health.service.MedicationToolService.PrescriptionCardView;
import com.zhiyu.health.service.MedicationToolService.PrescriptionItemView;
import com.zhiyu.health.service.prescription.DrugOrderService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 购药工具回调接口单测（票 77 → 票 88 药房感知化）：验证 controller 只做参数校验与装配、
 * 委托 service；prepare/otc-prepare 只读不扣库存，处方药不得走 OTC 路径。
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
                .andExpect(jsonPath("$.medications[0].specification").value("0.3g*20粒"));
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
                .andExpect(jsonPath("$.prescriptions[0].items[0].dosage").value("每次1粒"))
                .andExpect(jsonPath("$.prescriptions[0].items[0].quantity").value(2));
        verify(service).listApprovedPrescriptions(12L);
    }

    @Test
    void prepareReturnsLockedCampusPharmacyPreview() throws Exception {
        // 票 88：处方预览含锁定院区药房（嵌套契约形状 + 扁平确认页字段），只读不扣库存
        when(service.prepare(12L, 5L)).thenReturn(prescriptionPreview());

        mvc().perform(get("/api/agent/drug-orders/prepare")
                        .param("patient_id", "12")
                        .param("prescription_id", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PRESCRIPTION"))
                .andExpect(jsonPath("$.prescription_id").value(5))
                .andExpect(jsonPath("$.doctor_name").value("周安宁"))
                .andExpect(jsonPath("$.hospital_name").value("云澜医院"))
                .andExpect(jsonPath("$.campus_name").value("主院区"))
                .andExpect(jsonPath("$.pharmacy.id").value(71))
                .andExpect(jsonPath("$.pharmacy.display_name").value("主院区大药房"))
                .andExpect(jsonPath("$.items[0].medication_id").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.medication_amount").value(37.00));
        verify(service).prepare(12L, 5L);
    }

    @Test
    void otcPrepareEchoesValidatedItems() throws Exception {
        when(service.otcPrepare(eq(List.of(new DrugOrderService.QuantityInput(2L, 3)))))
                .thenReturn(List.of(new DrugOrderService.OtcItemEcho(2L, "布洛芬缓释胶囊", "0.3g*20粒", 3)));

        mvc().perform(get("/api/agent/drug-orders/otc-prepare")
                        .param("patient_id", "12")
                        .param("items", "2:3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].medication_id").value(2))
                .andExpect(jsonPath("$.items[0].name").value("布洛芬缓释胶囊"))
                .andExpect(jsonPath("$.items[0].quantity").value(3));
    }

    @Test
    void otcPrepareRejectsPrescriptionDrug() throws Exception {
        // ADR-0032 硬约束：处方药不得走 OTC 路径，service 抛 409，controller 零 try-catch 经 advice 出口。
        when(service.otcPrepare(eq(List.of(new DrugOrderService.QuantityInput(1L, 2)))))
                .thenThrow(new ApiException(409, "处方药须凭已审核电子处方购买"));

        mvc().perform(get("/api/agent/drug-orders/otc-prepare")
                        .param("patient_id", "12")
                        .param("items", "1:2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("处方药须凭已审核电子处方购买"));
    }

    @Test
    void otcPrepareRejectsMalformedItems() throws Exception {
        mvc().perform(get("/api/agent/drug-orders/otc-prepare")
                        .param("patient_id", "12")
                        .param("items", "not-a-pair"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("items 参数格式应为 medication_id:quantity 逗号分隔"));
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
        return new MedicationView(2L, "布洛芬缓释胶囊", "布洛芬", "0.3g*20粒");
    }

    private PrescriptionCardView approvedPrescription() {
        return new PrescriptionCardView(
                5L,
                "周安宁",
                "APPOINTMENT",
                "线下接诊",
                "2026-07-29",
                List.of(new PrescriptionItemView(1L, "阿莫西林胶囊", "0.25g*24粒", "每次1粒", "每日三次", "7天", 2)));
    }

    private DrugOrderService.PrescriptionPreviewView prescriptionPreview() {
        return new DrugOrderService.PrescriptionPreviewView(
                "PRESCRIPTION",
                5L,
                "周安宁",
                "2026-07-29",
                "云澜医院",
                "主院区",
                "澜山市城东区梧桐路1号",
                71L,
                "主院区大药房",
                new BigDecimal("5.00"),
                45,
                new DrugOrderService.PreviewPharmacyRef(71L, "主院区大药房", new BigDecimal("5.00"), 45),
                List.of(new DrugOrderService.PreviewItemView(
                        1L, "阿莫西林胶囊", "0.25g*24粒", 2, new BigDecimal("18.50"), 10, true)),
                new BigDecimal("37.00"));
    }
}
