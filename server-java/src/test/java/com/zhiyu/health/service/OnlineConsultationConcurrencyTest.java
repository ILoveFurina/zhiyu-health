package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.common.StaffUser;
import com.zhiyu.health.entity.consultation.OnlineConsultation;
import com.zhiyu.health.entity.consultation.OnlineConsultationMessage;
import com.zhiyu.health.mapper.chat.PreconsultationDraftMapper;
import com.zhiyu.health.mapper.common.InAppMessageMapper;
import com.zhiyu.health.mapper.common.StaffUserMapper;
import com.zhiyu.health.mapper.consultation.ConsultationRecordMapper;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMapper;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMessageMapper;
import com.zhiyu.health.mapper.health.HealthProfileAllergyMapper;
import com.zhiyu.health.mapper.prescription.PrescriptionMapper;
import com.zhiyu.health.service.common.MinioStorageService;
import com.zhiyu.health.service.consultation.OnlineConsultationService;
import com.zhiyu.health.service.consultation.mapping.OnlineConsultationDtoMapper;
import com.zhiyu.health.support.TestContracts;
import com.zhiyu.health.support.TestDisclaimers;
import java.time.OffsetDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 待接诊池并发接受：条件更新 affected rows 只有一行命中时，
 * 同科室多名医生竞争同一张 WAITING_DOCTOR 问诊单必须恰好一人成功（Spec 0003）。
 */
class OnlineConsultationConcurrencyTest {

    @Test
    void concurrentDoctorsCompetingForSameWaitingConsultationProduceExactlyOneAccept() throws Exception {
        OnlineConsultationMapper consultationMapper = mock(OnlineConsultationMapper.class);
        OnlineConsultationMessageMapper messageMapper = mock(OnlineConsultationMessageMapper.class);
        StaffUserMapper staffUserMapper = mock(StaffUserMapper.class);
        givenDoctor(staffUserMapper, 8L, 3L);
        givenDoctor(staffUserMapper, 9L, 4L);
        // 两名医生映射到同一标准科室，与问诊单科室一致
        when(consultationMapper.selectStandardDepartmentIdByDoctor(3L)).thenReturn(2L);
        when(consultationMapper.selectStandardDepartmentIdByDoctor(4L)).thenReturn(2L);
        when(consultationMapper.selectDetailedById(21L)).thenAnswer(invocation -> waitingConsultation());
        when(consultationMapper.expireOverdue(anyString(), anyString())).thenReturn(0);
        // 条件更新只允许一个赢家：模拟 PostgreSQL 单行 UPDATE ... WHERE status='WAITING_DOCTOR'
        AtomicBoolean claimed = new AtomicBoolean();
        AtomicInteger acceptWrites = new AtomicInteger();
        when(consultationMapper.accept(eq(21L), anyLong(), eq("WAITING_DOCTOR"), eq("IN_PROGRESS")))
                .thenAnswer(invocation -> {
                    acceptWrites.incrementAndGet();
                    return claimed.compareAndSet(false, true) ? 1 : 0;
                });
        AtomicInteger systemMessages = new AtomicInteger();
        when(messageMapper.insert(any(OnlineConsultationMessage.class))).thenAnswer(invocation -> {
            OnlineConsultationMessage message = invocation.getArgument(0);
            message.setId(99L);
            message.setCreatedAt(OffsetDateTime.now());
            systemMessages.incrementAndGet();
            return 1;
        });
        OnlineConsultationService service = new OnlineConsultationService(
                consultationMapper,
                messageMapper,
                mock(PreconsultationDraftMapper.class),
                staffUserMapper,
                mock(HealthProfileAllergyMapper.class),
                mock(ConsultationRecordMapper.class),
                serializedTransaction(),
                TestContracts.instance(),
                Mappers.getMapper(OnlineConsultationDtoMapper.class),
                mock(MinioStorageService.class),
                new ObjectMapper(),
                mock(PrescriptionMapper.class),
                mock(InAppMessageMapper.class),
                TestDisclaimers.instance());

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(10);
        try {
            // 两名同科室医生各起 5 个并发请求抢同一张单
            for (int i = 0; i < 10; i++) {
                long staffId = i % 2 == 0 ? 8L : 9L;
                executor.submit(() -> {
                    try {
                        service.accept(staffId, 21L);
                        successes.incrementAndGet();
                    } catch (ApiException e) {
                        assertThat(e.getStatus()).isEqualTo(409);
                        conflicts.incrementAndGet();
                    }
                });
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes).hasValue(1);
        assertThat(conflicts).hasValue(9);
        // 接受写与系统消息都只发生一次：不存在双医生同时接诊的脏写
        assertThat(acceptWrites.get()).isGreaterThanOrEqualTo(1);
        assertThat(systemMessages).hasValue(1);
    }

    /** 行锁语义：条件更新的 check-and-set 在真实库中是单条 UPDATE，这里用监视器串行化等价模拟。 */
    private TransactionTemplate serializedTransaction() {
        Object rowLock = new Object();
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            synchronized (rowLock) {
                return callback.doInTransaction(mock(TransactionStatus.class));
            }
        });
        return template;
    }

    private void givenDoctor(StaffUserMapper staffUserMapper, long staffId, long doctorId) {
        StaffUser staff = new StaffUser();
        staff.setId(staffId);
        staff.setRole(StaffUser.ROLE_DOCTOR);
        staff.setDoctorId(doctorId);
        when(staffUserMapper.selectById(staffId)).thenReturn(staff);
    }

    private OnlineConsultation waitingConsultation() {
        OnlineConsultation consultation = new OnlineConsultation();
        consultation.setId(21L);
        consultation.setPatientId(12L);
        consultation.setHealthProfileId(3L);
        consultation.setDraftId(5L);
        consultation.setStandardDepartmentId(2L);
        consultation.setStandardDepartmentName("呼吸内科");
        consultation.setChiefComplaint("咳嗽三天");
        consultation.setStatus("WAITING_DOCTOR");
        consultation.setExpiresAt(OffsetDateTime.now().plusSeconds(600));
        return consultation;
    }
}
