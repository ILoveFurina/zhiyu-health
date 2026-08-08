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
import com.zhiyu.health.controller.b.PaymentController;
import com.zhiyu.health.controller.mapping.AppointmentCardMapper;
import com.zhiyu.health.entity.Appointment;
import com.zhiyu.health.entity.HealthProfile;
import com.zhiyu.health.entity.Payment;
import com.zhiyu.health.entity.TimeSlot;
import com.zhiyu.health.mapper.AppointmentMapper;
import com.zhiyu.health.mapper.PaymentMapper;
import com.zhiyu.health.mapper.ScheduleMapper;
import com.zhiyu.health.service.AppointmentService;
import com.zhiyu.health.service.HealthProfileService;
import com.zhiyu.health.service.PaymentService;
import com.zhiyu.health.service.SlotAccounting;
import com.zhiyu.health.service.SlotWindowGuard;
import com.zhiyu.health.service.mapping.AppointmentDtoMapper;
import com.zhiyu.health.service.mapping.PaymentDtoMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
        AtomicReference<Payment> stored = new AtomicReference<>(payment("UNPAID"));
        when(mapper.selectForPatientForUpdate(21L, 12L)).thenAnswer(ignored -> copy(stored.get()));
        when(mapper.markPaid(21L, "PAID", "UNPAID")).thenAnswer(ignored -> {
            Payment persisted = copy(stored.get());
            persisted.setStatus("PAID");
            persisted.setPaidAt(OffsetDateTime.parse("2026-08-02T10:05:00+08:00"));
            stored.set(persisted);
            return 1;
        });
        when(mapper.selectById(41L)).thenAnswer(ignored -> copy(stored.get()));
        AppointmentService appointments = appointmentService(stored);
        AppointmentController appointmentController = new AppointmentController(
                appointments, TestDisclaimers.instance(), Mappers.getMapper(AppointmentCardMapper.class));
        MockMvc flowMvc = mvc(controller, appointmentController, new PaymentController(service));

        flowMvc.perform(post("/api/c/appointments/21/payment/pay").requestAttr("authSubject", 12L))
                .andExpect(status().isOk());
        flowMvc.perform(get("/api/b/payments/41"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paid_at").isNotEmpty());
        flowMvc.perform(get("/api/c/appointments").requestAttr("authSubject", 12L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].payment_status").value("PAID"))
                .andExpect(jsonPath("$[0].payment_status_label").value("已支付"))
                .andExpect(jsonPath("$[0].payment_payable").value(false));
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

    private Payment copy(Payment source) {
        Payment copy = new Payment();
        copy.setId(source.getId());
        copy.setAppointmentId(source.getAppointmentId());
        copy.setAmount(source.getAmount());
        copy.setStatus(source.getStatus());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setPaidAt(source.getPaidAt());
        return copy;
    }

    private AppointmentService appointmentService(AtomicReference<Payment> stored) {
        AppointmentMapper appointments = mock(AppointmentMapper.class);
        HealthProfileService healthProfiles = mock(HealthProfileService.class);
        HealthProfile profile = new HealthProfile();
        profile.setId(31L);
        when(healthProfiles.requireActive(12L)).thenReturn(profile);
        when(appointments.selectViewsByProfile(12L, 31L))
                .thenAnswer(ignored -> List.of(appointment(stored.get().getStatus())));
        return new AppointmentService(
                appointments,
                mock(ScheduleMapper.class),
                mock(com.zhiyu.health.mapper.ScheduleRequestMapper.class),
                mock(com.zhiyu.health.mapper.InAppMessageMapper.class),
                mock(SlotAccounting.class),
                transactionTemplate(),
                healthProfiles,
                service,
                TestContracts.instance(),
                Mappers.getMapper(AppointmentDtoMapper.class),
                TestDisclaimers.instance(),
                new ObjectMapper(),
                new SlotWindowGuard(TestContracts.instance(), java.time.Clock.systemDefaultZone()));
    }

    private Appointment appointment(String paymentStatus) {
        Appointment appointment = new Appointment();
        appointment.setId(21L);
        appointment.setScheduleId(9L);
        appointment.setDoctorId(2L);
        appointment.setDoctorName("周安宁");
        appointment.setDepartmentName("心血管内科");
        appointment.setScheduleDate(LocalDate.parse("2026-07-29"));
        appointment.setTimeSlot(TimeSlot.MORNING);
        appointment.setSequenceNumber(1);
        appointment.setStatus(Appointment.STATUS_BOOKED);
        appointment.setRegistrationFee(new BigDecimal("30.00"));
        appointment.setPaymentStatus(paymentStatus);
        appointment.setConditionSummary("主诉胸闷两天");
        appointment.setCreatedAt(OffsetDateTime.parse("2026-07-28T10:00:00+08:00"));
        return appointment;
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
