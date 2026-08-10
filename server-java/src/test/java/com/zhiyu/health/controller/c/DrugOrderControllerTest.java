package com.zhiyu.health.controller.c;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.controller.patient.prescription.DrugOrderController;
import com.zhiyu.health.controller.patient.prescription.mapping.DrugOrderInputMapper;
import com.zhiyu.health.service.prescription.DrugOrderService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/** C 端药品订单端点冒烟（票 88）：薄入口装配与参数解析；业务行为见 DrugOrderServiceTest。 */
class DrugOrderControllerTest {

    private final DrugOrderService service = mock(DrugOrderService.class);
    private final DrugOrderController controller =
            new DrugOrderController(service, Mappers.getMapper(DrugOrderInputMapper.class));

    @Test
    void previewDelegatesToService() throws Exception {
        DrugOrderService.PrescriptionPreviewView preview = new DrugOrderService.PrescriptionPreviewView(
                "PRESCRIPTION",
                31L,
                "林医生",
                "2026-08-10",
                "云澜医院",
                "主院区",
                "澜山市城东区梧桐路1号",
                71L,
                "主院区大药房",
                new BigDecimal("5.00"),
                45,
                new DrugOrderService.PreviewPharmacyRef(71L, "主院区大药房", new BigDecimal("5.00"), 45),
                List.of(),
                new BigDecimal("37.00"));
        when(service.previewPrescription(7L, 31L)).thenReturn(preview);

        mvc().perform(get("/api/c/drug-orders/preview")
                        .param("prescription_id", "31")
                        .requestAttr("authSubject", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PRESCRIPTION"))
                .andExpect(jsonPath("$.pharmacy_id").value(71))
                .andExpect(jsonPath("$.pharmacy_name").value("主院区大药房"))
                .andExpect(jsonPath("$.pharmacy.id").value(71))
                .andExpect(jsonPath("$.pharmacy.display_name").value("主院区大药房"))
                .andExpect(jsonPath("$.medication_amount").value(37.00));
    }

    @Test
    void otcCandidatesParsesItemsParamAndPassesCoords() throws Exception {
        DrugOrderService.OtcCandidatesView candidates = new DrugOrderService.OtcCandidatesView(
                List.of(new DrugOrderService.OtcItemEcho(2L, "布洛芬缓释胶囊", "0.3g*20粒", 3)),
                List.of(new DrugOrderService.OtcCandidateView(
                        71L,
                        "主院区大药房",
                        "云澜医院",
                        "主院区",
                        "澜山市城东区梧桐路1号",
                        new BigDecimal("5.00"),
                        45,
                        new BigDecimal("66.00"),
                        320.5)));
        when(service.otcCandidates(List.of(new DrugOrderService.QuantityInput(2L, 3)), 120.15, 30.27))
                .thenReturn(candidates);

        mvc().perform(get("/api/c/drug-orders/otc-candidates")
                        .param("items", "2:3")
                        .param("lng", "120.15")
                        .param("lat", "30.27")
                        .requestAttr("authSubject", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].medication_id").value(2))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.pharmacies[0].pharmacy_id").value(71))
                .andExpect(jsonPath("$.pharmacies[0].medication_amount").value(66.00))
                .andExpect(jsonPath("$.pharmacies[0].distance_meters").value(320.5));
    }

    @Test
    void otcCandidatesWithoutCoordsOmitsDistance() throws Exception {
        DrugOrderService.OtcCandidatesView candidates = new DrugOrderService.OtcCandidatesView(
                List.of(new DrugOrderService.OtcItemEcho(2L, "布洛芬缓释胶囊", "0.3g*20粒", 3)),
                List.of(new DrugOrderService.OtcCandidateView(
                        71L,
                        "主院区大药房",
                        "云澜医院",
                        "主院区",
                        "澜山市城东区梧桐路1号",
                        new BigDecimal("5.00"),
                        45,
                        new BigDecimal("66.00"),
                        null)));
        when(service.otcCandidates(eq(List.of(new DrugOrderService.QuantityInput(2L, 3))), isNull(), isNull()))
                .thenReturn(candidates);

        mvc().perform(get("/api/c/drug-orders/otc-candidates")
                        .param("items", "2:3")
                        .requestAttr("authSubject", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pharmacies[0].pharmacy_id").value(71))
                .andExpect(jsonPath("$.pharmacies[0].distance_meters").doesNotExist());
    }

    @Test
    void otcCandidatesRejectsMalformedItems() throws Exception {
        mvc().perform(get("/api/c/drug-orders/otc-candidates")
                        .param("items", "not-a-pair")
                        .requestAttr("authSubject", 7L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("items 参数格式应为 medication_id:quantity 逗号分隔"));
    }

    @Test
    void createMapsInputToCommand() throws Exception {
        DrugOrderService.OrderView order = orderView("UNPAID");
        when(service.create(any())).thenReturn(order);

        mvc().perform(
                        post("/api/c/drug-orders")
                                .requestAttr("authSubject", 7L)
                                .contentType("application/json")
                                .content(
                                        """
                                {"prescription_id":31,"pickup_method":"DELIVERY",
                                 "receiver":{"name":"张三","phone":"13812345678","address":"澜山市城东区梧桐路12号"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(51))
                .andExpect(jsonPath("$.status").value("UNPAID"))
                .andExpect(jsonPath("$.pharmacy.display_name").value("主院区大药房"))
                .andExpect(jsonPath("$.events[0].status").value("UNPAID"));

        org.mockito.Mockito.verify(service)
                .create(eq(new DrugOrderService.CreateCommand(
                        7L,
                        31L,
                        null,
                        null,
                        "DELIVERY",
                        new DrugOrderService.ReceiverInput("张三", "13812345678", "澜山市城东区梧桐路12号"))));
    }

    @Test
    void createOtcMapsPharmacyAndItems() throws Exception {
        when(service.create(any())).thenReturn(orderView("UNPAID"));

        mvc().perform(
                        post("/api/c/drug-orders")
                                .requestAttr("authSubject", 7L)
                                .contentType("application/json")
                                .content(
                                        """
                                {"pharmacy_id":71,"items":[{"medication_id":2,"quantity":3}],"pickup_method":"PICKUP"}
                                """))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(service)
                .create(eq(new DrugOrderService.CreateCommand(
                        7L, null, 71L, List.of(new DrugOrderService.QuantityInput(2L, 3)), "PICKUP", null)));
    }

    @Test
    void payCancelListAndDetailDelegate() throws Exception {
        when(service.pay(7L, 51L)).thenReturn(orderView("PAID"));
        when(service.cancel(7L, 51L)).thenReturn(orderView("CANCELLED"));
        when(service.listForPatient(7L)).thenReturn(List.of(orderView("UNPAID")));
        when(service.detailForPatient(7L, 51L)).thenReturn(orderView("PAID"));

        mvc().perform(post("/api/c/drug-orders/51/pay").requestAttr("authSubject", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        mvc().perform(post("/api/c/drug-orders/51/cancel").requestAttr("authSubject", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc().perform(get("/api/c/drug-orders").requestAttr("authSubject", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status_label").value("待支付"));
        mvc().perform(get("/api/c/drug-orders/51").requestAttr("authSubject", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void serviceExceptionPropagatesThroughAdvice() throws Exception {
        when(service.pay(7L, 51L)).thenThrow(new ApiException(409, "仅待支付药品订单可支付"));

        mvc().perform(post("/api/c/drug-orders/51/pay").requestAttr("authSubject", 7L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("仅待支付药品订单可支付"));
    }

    private DrugOrderService.OrderView orderView(String status) {
        return new DrugOrderService.OrderView(
                51L,
                7L,
                null,
                31L,
                "PRESCRIPTION",
                status,
                "待支付",
                new DrugOrderService.PharmacyRef(71L, "主院区大药房"),
                "主院区大药房",
                "云澜医院",
                "主院区",
                "澜山市城东区梧桐路1号",
                "澜山市城东区梧桐路1号",
                "DELIVERY",
                "配送到家",
                new BigDecimal("37.00"),
                new BigDecimal("5.00"),
                new BigDecimal("42.00"),
                "2026-08-10T10:10:00+08:00",
                550L,
                null,
                "张三",
                "138****5678",
                "澜山市城东区****",
                null,
                null,
                "UNPAID".equals(status),
                "UNPAID".equals(status),
                List.of(),
                List.of(new DrugOrderService.EventView("UNPAID", "待支付", "2026-08-10T10:00:00+08:00")),
                "2026-08-10T10:00:00+08:00");
    }

    private MockMvc mvc() {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }
}
