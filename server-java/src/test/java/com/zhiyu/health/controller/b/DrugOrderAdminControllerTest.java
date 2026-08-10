package com.zhiyu.health.controller.b;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.zhiyu.health.controller.staff.prescription.DrugOrderAdminController;
import com.zhiyu.health.service.prescription.DrugOrderService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * B 端药品订单端点冒烟（票 88）：薄入口装配与分页/过滤参数传递、staffId 注入履约、
 * 取消与履约动作委托；业务行为见 DrugOrderServiceTest。
 */
class DrugOrderAdminControllerTest {

    private final DrugOrderService service = mock(DrugOrderService.class);
    private final DrugOrderAdminController controller = new DrugOrderAdminController(service);

    @Test
    void listPassesFiltersAndPagination() throws Exception {
        when(service.listForAdmin("PAID", "DELIVERY", 2, 10))
                .thenReturn(new DrugOrderService.AdminOrderPage(List.of(orderView("PAID")), 21, 2, 10));

        mvc().perform(get("/api/b/drug-orders")
                        .param("status", "PAID")
                        .param("pickup_method", "DELIVERY")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(21))
                .andExpect(jsonPath("$.records[0].id").value(51))
                .andExpect(jsonPath("$.records[0].status").value("PAID"))
                .andExpect(jsonPath("$.records[0].status_label").value("已支付"))
                .andExpect(jsonPath("$.records[0].patient_name").value("张三"));
        verify(service).listForAdmin("PAID", "DELIVERY", 2, 10);
    }

    @Test
    void listWithoutFiltersDelegatesNulls() throws Exception {
        when(service.listForAdmin(isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(new DrugOrderService.AdminOrderPage(List.of(), 0, 1, 20));

        mvc().perform(get("/api/b/drug-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isArray());
    }

    @Test
    void detailReturnsPlaintextReceiverForFulfillment() throws Exception {
        when(service.detailForAdmin(51L)).thenReturn(orderView("SHIPPED"));

        mvc().perform(get("/api/b/drug-orders/51"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(51))
                .andExpect(jsonPath("$.prescription_id").value(31))
                .andExpect(jsonPath("$.receiver_phone").value("13812345678"))
                .andExpect(jsonPath("$.carrier_name").value("智愈模拟配送"))
                .andExpect(jsonPath("$.events[0].status").value("UNPAID"))
                .andExpect(jsonPath("$.events[0].occurred_at").exists());
    }

    @Test
    void fulfillmentInjectsStaffIdAndDelegatesDecision() throws Exception {
        when(service.fulfill(9L, 51L, "DISPENSE")).thenReturn(orderView("DISPENSING"));

        mvc().perform(post("/api/b/drug-orders/51/fulfillment")
                        .requestAttr("authSubject", 9L)
                        .contentType("application/json")
                        .content("{\"decision\":\"DISPENSE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPENSING"));
        verify(service).fulfill(9L, 51L, "DISPENSE");
    }

    @Test
    void cancelDelegatesToService() throws Exception {
        when(service.cancelForAdmin(51L)).thenReturn(orderView("CANCELLED"));

        mvc().perform(post("/api/b/drug-orders/51/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(service).cancelForAdmin(51L);
    }

    private DrugOrderService.OrderView orderView(String status) {
        return new DrugOrderService.OrderView(
                51L,
                7L,
                "张三",
                31L,
                "PRESCRIPTION",
                status,
                "已支付",
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
                null,
                null,
                "张三",
                "13812345678",
                "澜山市城东区梧桐路12号3栋",
                "智愈模拟配送",
                "ZY0000000051",
                false,
                false,
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
