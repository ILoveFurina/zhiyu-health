package com.zhiyu.health.controller.b;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.zhiyu.health.config.ApiExceptionHandler;
import com.zhiyu.health.entity.Payment;
import com.zhiyu.health.mapper.PaymentMapper;
import com.zhiyu.health.service.PaymentService;
import com.zhiyu.health.service.mapping.PaymentDtoMapper;
import com.zhiyu.health.support.TestContracts;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentControllerTest {

    private final PaymentMapper mapper = mock(PaymentMapper.class);
    private final PaymentDtoMapper dtoMapper = Mappers.getMapper(PaymentDtoMapper.class);
    private final PaymentService service =
            new PaymentService(mapper, transactionTemplate(), TestContracts.instance(), dtoMapper);
    private final PaymentController controller = new PaymentController(service);

    @Test
    void filtersPaymentsByContractStatus() throws Exception {
        when(mapper.selectForAdmin("PAID")).thenReturn(List.of(payment("PAID")));

        mvc().perform(get("/api/b/payments").param("status", "PAID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(41))
                .andExpect(jsonPath("$[0].appointment_id").value(21))
                .andExpect(jsonPath("$[0].status").value("PAID"))
                .andExpect(jsonPath("$[0].status_label").value("已支付"));
    }

    @Test
    void getsPaymentDetail() throws Exception {
        when(mapper.selectById(41L)).thenReturn(payment("UNPAID"));

        mvc().perform(get("/api/b/payments/41"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(41))
                .andExpect(jsonPath("$.appointment_id").value(21))
                .andExpect(jsonPath("$.amount").value(30.00))
                .andExpect(jsonPath("$.payable").value(true));
    }

    @Test
    void adminPaysUnpaidAppointmentFee() throws Exception {
        when(mapper.selectForUpdate(41L)).thenReturn(payment("UNPAID"));
        when(mapper.markPaid(21L, "PAID", "UNPAID")).thenReturn(1);

        mvc().perform(post("/api/b/payments/41/pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.status_label").value("已支付"))
                .andExpect(jsonPath("$.paid_at").isNotEmpty());
    }

    private Payment payment(String status) {
        Payment payment = new Payment();
        payment.setId(41L);
        payment.setAppointmentId(21L);
        payment.setAmount(new BigDecimal("30.00"));
        payment.setStatus(status);
        payment.setCreatedAt(OffsetDateTime.parse("2026-08-02T10:00:00+08:00"));
        return payment;
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
        when(template.execute(any())).thenAnswer(invocation -> invocation
                .getArgument(0, TransactionCallback.class)
                .doInTransaction(mock(TransactionStatus.class)));
        return template;
    }
}
