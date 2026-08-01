package com.zhiyu.health.controller.c;

import static org.mockito.ArgumentMatchers.any;
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
import com.zhiyu.health.controller.mapping.AppointmentCardMapper;
import com.zhiyu.health.entity.Payment;
import com.zhiyu.health.mapper.PaymentMapper;
import com.zhiyu.health.service.AppointmentService;
import com.zhiyu.health.service.PaymentService;
import com.zhiyu.health.service.mapping.PaymentDtoMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
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

class AppointmentPaymentControllerTest {

    private final PaymentMapper mapper = mock(PaymentMapper.class);
    private final PaymentDtoMapper dtoMapper = Mappers.getMapper(PaymentDtoMapper.class);
    private final PaymentService service =
            new PaymentService(mapper, transactionTemplate(), TestContracts.instance(), dtoMapper);
    private final AppointmentPaymentController controller = new AppointmentPaymentController(service);

    @Test
    void currentPatientPaysUnpaidAppointmentFee() throws Exception {
        Payment payment = payment("UNPAID");
        when(mapper.selectForPatientForUpdate(21L, 12L)).thenReturn(payment);
        when(mapper.markPaid(21L, "PAID", "UNPAID")).thenReturn(1);

        mvc().perform(post("/api/c/appointments/21/payment/pay").requestAttr("authSubject", 12L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointment_id").value(21))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.status_label").value("已支付"))
                .andExpect(jsonPath("$.paid_at").isNotEmpty());
    }

    @Test
    void paymentImmediatelySynchronizesAppointmentCardStatus() throws Exception {
        Payment payment = payment("UNPAID");
        when(mapper.selectForPatientForUpdate(21L, 12L)).thenReturn(payment);
        when(mapper.markPaid(21L, "PAID", "UNPAID")).thenReturn(1);
        AppointmentService appointments = mock(AppointmentService.class);
        when(appointments.listForPatient(12L)).thenAnswer(ignored -> List.of(appointment(payment.getStatus())));
        AppointmentController appointmentController = new AppointmentController(
                appointments,
                TestDisclaimers.instance(),
                Mappers.getMapper(AppointmentCardMapper.class));
        MockMvc flowMvc = mvc(controller, appointmentController);

        flowMvc.perform(post("/api/c/appointments/21/payment/pay").requestAttr("authSubject", 12L))
                .andExpect(status().isOk());
        flowMvc.perform(get("/api/c/appointments").requestAttr("authSubject", 12L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].payment_status").value("PAID"))
                .andExpect(jsonPath("$[0].payment_status_label").value("已支付"));
    }

    @Test
    void patientCannotPayAnotherPatientsFee() throws Exception {
        when(mapper.selectForPatientForUpdate(21L, 12L)).thenReturn(null);

        mvc().perform(post("/api/c/appointments/21/payment/pay").requestAttr("authSubject", 12L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("挂号收费不存在"));

        verify(mapper, never()).markPaid(21L, "PAID", "UNPAID");
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

    private AppointmentService.AppointmentView appointment(String paymentStatus) {
        return new AppointmentService.AppointmentView(
                21L,
                9L,
                2L,
                "周安宁",
                "心血管内科",
                "2026-07-29",
                "上午",
                1,
                "已约",
                new BigDecimal("30.00"),
                paymentStatus,
                TestContracts.instance().paymentFlow().statusLabels().get(paymentStatus),
                "主诉胸闷两天",
                "2026-07-28T10:00:00+08:00");
    }

    private MockMvc mvc() {
        return mvc(controller);
    }

    private MockMvc mvc(Object... controllers) {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return standaloneSetup(controllers)
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
