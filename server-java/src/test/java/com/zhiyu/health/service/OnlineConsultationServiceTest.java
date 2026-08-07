package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.OnlineConsultation;
import com.zhiyu.health.entity.OnlineConsultationMessage;
import com.zhiyu.health.entity.PreconsultationDraft;
import com.zhiyu.health.entity.StaffUser;
import com.zhiyu.health.mapper.HealthProfileAllergyMapper;
import com.zhiyu.health.mapper.OnlineConsultationMapper;
import com.zhiyu.health.mapper.OnlineConsultationMessageMapper;
import com.zhiyu.health.mapper.PreconsultationDraftMapper;
import com.zhiyu.health.mapper.StaffUserMapper;
import com.zhiyu.health.service.mapping.OnlineConsultationDtoMapper;
import com.zhiyu.health.support.TestContracts;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 票 54 在线问诊模块：状态机、归属、幂等与并发出口（mapper 全 mock，行为经模块 interface 断言）。 */
class OnlineConsultationServiceTest {

    // ------------------------------------------------------------------
    // 确认建单
    // ------------------------------------------------------------------

    @Test
    void confirmOnSubmittedDraftReturnsLatestConsultationWithoutInsert() {
        Fixture f = new Fixture();
        PreconsultationDraft draft = f.draft("SUBMITTED");
        when(f.draftMapper.selectById(5L)).thenReturn(draft);
        OnlineConsultation existing = f.consultation("WAITING_DOCTOR");
        when(f.consultationMapper.selectLatestByDraftId(5L)).thenReturn(existing);

        OnlineConsultationService.ConsultationDetail detail = f.service.confirm(12L, 5L);

        assertThat(detail.id()).isEqualTo(21L);
        assertThat(detail.status()).isEqualTo("WAITING_DOCTOR");
        verify(f.consultationMapper, never()).insert(any(OnlineConsultation.class));
        verify(f.draftMapper, never()).markSubmitted(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void confirmCreatesConsultationAndMarksDraftSubmittedInOneTransaction() {
        Fixture f = new Fixture();
        when(f.draftMapper.selectById(5L)).thenReturn(f.draft("PENDING_CONFIRM"));
        OnlineConsultation stored = f.consultation("WAITING_DOCTOR");
        when(f.consultationMapper.selectDetailedById(21L)).thenReturn(stored);
        when(f.draftMapper.markSubmitted(eq(5L), eq("SUBMITTED"), eq("COLLECTING"), eq("PENDING_CONFIRM")))
                .thenReturn(1);

        OnlineConsultationService.ConsultationDetail detail = f.service.confirm(12L, 5L);

        ArgumentCaptor<OnlineConsultation> inserted = ArgumentCaptor.forClass(OnlineConsultation.class);
        verify(f.consultationMapper).insert(inserted.capture());
        OnlineConsultation consultation = inserted.getValue();
        assertThat(consultation.getStatus()).isEqualTo("WAITING_DOCTOR");
        assertThat(consultation.getHealthProfileId()).isEqualTo(3L);
        assertThat(consultation.getDraftId()).isEqualTo(5L);
        assertThat(consultation.getConversationId()).isEqualTo(77L);
        assertThat(consultation.getStandardDepartmentId()).isEqualTo(2L);
        assertThat(consultation.getChiefComplaint()).isEqualTo("咳嗽三天");
        assertThat(consultation.getSummaryDisclaimer()).isEqualTo("仅供参考，不替代医生诊断");
        // 接诊截止时间 = 创建 + 契约 600 秒（断言窗口而非绝对时钟）
        assertThat(consultation.getExpiresAt())
                .isBetween(
                        OffsetDateTime.now().plusSeconds(590),
                        OffsetDateTime.now().plusSeconds(610));
        verify(f.draftMapper).markSubmitted(5L, "SUBMITTED", "COLLECTING", "PENDING_CONFIRM");
        assertThat(detail.id()).isEqualTo(21L);
        assertThat(detail.progressStep()).isEqualTo("WAITING_DOCTOR");
    }

    @Test
    void confirmRequiresSummarySnapshotAndResolvedDepartment() {
        Fixture f = new Fixture();
        PreconsultationDraft noSummary = f.draft("COLLECTING");
        noSummary.setSummaryUpdatedAt(null);
        when(f.draftMapper.selectById(5L)).thenReturn(noSummary);
        assertThatThrownBy(() -> f.service.confirm(12L, 5L)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(409);
            assertThat(e.getMessage()).isEqualTo("请先与 AI 完成预问诊病情摘要");
        });

        PreconsultationDraft noDepartment = f.draft("PENDING_CONFIRM");
        noDepartment.setSuggestedStandardDepartmentId(null);
        when(f.draftMapper.selectById(6L)).thenReturn(noDepartment);
        assertThatThrownBy(() -> f.service.confirm(12L, 6L)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(409);
            assertThat(e.getMessage()).isEqualTo("请继续完善预问诊信息，暂未确定建议科室");
        });
        verify(f.consultationMapper, never()).insert(any(OnlineConsultation.class));
    }

    @Test
    void confirmForeignDraftYields404() {
        Fixture f = new Fixture();
        PreconsultationDraft foreign = f.draft("PENDING_CONFIRM");
        foreign.setPatientId(99L);
        when(f.draftMapper.selectById(5L)).thenReturn(foreign);

        assertThatThrownBy(() -> f.service.confirm(12L, 5L))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void confirmConcurrentDuplicateReturnsExistingActiveConsultation() {
        Fixture f = new Fixture();
        when(f.draftMapper.selectById(5L)).thenReturn(f.draft("PENDING_CONFIRM"));
        when(f.consultationMapper.insert(any(OnlineConsultation.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_online_consultations_active_profile\""));
        OnlineConsultation active = f.consultation("WAITING_DOCTOR");
        when(f.consultationMapper.selectActiveByProfile(3L, "WAITING_DOCTOR", "IN_PROGRESS"))
                .thenReturn(active);

        OnlineConsultationService.ConsultationDetail detail = f.service.confirm(12L, 5L);

        // 并发确认撞部分唯一索引：幂等回放既有活跃单，不产生第二张问诊单
        assertThat(detail.id()).isEqualTo(21L);
        verify(f.draftMapper, never()).markSubmitted(anyLong(), anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // 取消与重新提交
    // ------------------------------------------------------------------

    @Test
    void cancelTransitionsWaitingToCancelled() {
        Fixture f = new Fixture();
        when(f.consultationMapper.selectDetailedByIdAndPatient(21L, 12L)).thenReturn(f.consultation("WAITING_DOCTOR"));
        when(f.consultationMapper.cancel(21L, 12L, "WAITING_DOCTOR", "CANCELLED"))
                .thenReturn(1);
        when(f.consultationMapper.selectDetailedById(21L)).thenReturn(f.consultation("CANCELLED"));

        OnlineConsultationService.ConsultationDetail detail = f.service.cancel(12L, 21L);

        assertThat(detail.status()).isEqualTo("CANCELLED");
        assertThat(detail.progressStep()).isNull();
        assertThat(detail.terminalHint()).isEqualTo("问诊已取消。可复用原病情摘要重新提交，无需重复预问诊。");
    }

    @Test
    void cancelIsIdempotentWhenAlreadyCancelled() {
        Fixture f = new Fixture();
        when(f.consultationMapper.selectDetailedByIdAndPatient(21L, 12L)).thenReturn(f.consultation("CANCELLED"));

        OnlineConsultationService.ConsultationDetail detail = f.service.cancel(12L, 21L);

        assertThat(detail.status()).isEqualTo("CANCELLED");
        verify(f.consultationMapper, never()).cancel(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void cancelRejectsNonWaiting() {
        Fixture f = new Fixture();
        when(f.consultationMapper.selectDetailedByIdAndPatient(21L, 12L)).thenReturn(f.consultation("IN_PROGRESS"));

        assertThatThrownBy(() -> f.service.cancel(12L, 21L)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(409);
            assertThat(e.getMessage()).isEqualTo("问诊单不在等待接诊状态");
        });
    }

    @Test
    void resubmitFromTerminalCreatesFreshWaitingConsultation() {
        Fixture f = new Fixture();
        when(f.consultationMapper.selectDetailedByIdAndPatient(21L, 12L)).thenReturn(f.consultation("EXPIRED"));
        // Fixture 的 insert 答案对 draftId=5 回填 id 21，重读按同一 id 打桩
        when(f.consultationMapper.selectDetailedById(21L)).thenReturn(f.consultation("WAITING_DOCTOR"));

        OnlineConsultationService.ConsultationDetail detail = f.service.resubmit(12L, 21L);

        ArgumentCaptor<OnlineConsultation> inserted = ArgumentCaptor.forClass(OnlineConsultation.class);
        verify(f.consultationMapper).insert(inserted.capture());
        OnlineConsultation fresh = inserted.getValue();
        assertThat(fresh.getId()).isEqualTo(21L);
        assertThat(fresh.getStatus()).isEqualTo("WAITING_DOCTOR");
        // 复用原摘要/科室/档案，只刷新状态与截止时间
        assertThat(fresh.getChiefComplaint()).isEqualTo("咳嗽三天");
        assertThat(fresh.getStandardDepartmentId()).isEqualTo(2L);
        assertThat(fresh.getExpiresAt()).isAfter(OffsetDateTime.now());
        assertThat(detail.status()).isEqualTo("WAITING_DOCTOR");
    }

    @Test
    void resubmitRejectsActiveSource() {
        Fixture f = new Fixture();
        when(f.consultationMapper.selectDetailedByIdAndPatient(21L, 12L)).thenReturn(f.consultation("IN_PROGRESS"));

        assertThatThrownBy(() -> f.service.resubmit(12L, 21L))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(409));
        verify(f.consultationMapper, never()).insert(any(OnlineConsultation.class));
    }

    @Test
    void resubmitUniqueViolationReturnsExistingActiveConsultation() {
        Fixture f = new Fixture();
        when(f.consultationMapper.selectDetailedByIdAndPatient(21L, 12L)).thenReturn(f.consultation("CANCELLED"));
        when(f.consultationMapper.insert(any(OnlineConsultation.class)))
                .thenThrow(new DataIntegrityViolationException("uq_online_consultations_active_profile"));
        when(f.consultationMapper.selectActiveByProfile(3L, "WAITING_DOCTOR", "IN_PROGRESS"))
                .thenReturn(f.consultation("IN_PROGRESS"));

        OnlineConsultationService.ConsultationDetail detail = f.service.resubmit(12L, 21L);

        assertThat(detail.status()).isEqualTo("IN_PROGRESS");
    }

    // ------------------------------------------------------------------
    // B 端：科室池、可见性、原子接受
    // ------------------------------------------------------------------

    @Test
    void poolRequiresMappableStandardDepartment() {
        Fixture f = new Fixture();
        f.givenDoctor(8L, 3L);
        when(f.consultationMapper.selectStandardDepartmentIdByDoctor(3L)).thenReturn(null);

        assertThatThrownBy(() -> f.service.pool(8L))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void poolSweepsExpiredAndScopesByDoctorStandardDepartment() {
        Fixture f = new Fixture();
        f.givenDoctor(8L, 3L);
        when(f.consultationMapper.selectStandardDepartmentIdByDoctor(3L)).thenReturn(2L);
        when(f.consultationMapper.selectPool(2L, "WAITING_DOCTOR"))
                .thenReturn(List.of(f.consultation("WAITING_DOCTOR")));
        when(f.allergyMapper.selectAllergens(3L)).thenReturn(List.of("青霉素"));

        List<OnlineConsultationService.DoctorListItem> pool = f.service.pool(8L);

        assertThat(pool).hasSize(1);
        assertThat(pool.get(0).standardDepartmentId()).isEqualTo(2L);
        assertThat(pool.get(0).healthProfile().allergies()).containsExactly("青霉素");
        // 进入模块先惰性收敛过期待接诊单
        verify(f.consultationMapper).expireOverdue("WAITING_DOCTOR", "EXPIRED");
    }

    @Test
    void doctorDetailVisibilityBoundOrSameDepartmentPoolOnly() {
        Fixture f = new Fixture();
        f.givenDoctor(8L, 3L);
        when(f.allergyMapper.selectAllergens(anyLong())).thenReturn(List.of());
        // 绑定医生可见
        OnlineConsultation bound = f.consultation("IN_PROGRESS");
        when(f.consultationMapper.selectDetailedById(21L)).thenReturn(bound);
        assertThat(f.service.detailForDoctor(8L, 21L).id()).isEqualTo(21L);
        // 同标准科室待接诊单可见
        when(f.consultationMapper.selectStandardDepartmentIdByDoctor(3L)).thenReturn(2L);
        OnlineConsultation sameDeptWaiting = f.consultation("WAITING_DOCTOR");
        sameDeptWaiting.setId(22L);
        when(f.consultationMapper.selectDetailedById(22L)).thenReturn(sameDeptWaiting);
        assertThat(f.service.detailForDoctor(8L, 22L).id()).isEqualTo(22L);
        // 跨科室待接诊单不可见
        OnlineConsultation crossDept = f.consultation("WAITING_DOCTOR");
        crossDept.setId(23L);
        crossDept.setStandardDepartmentId(9L);
        when(f.consultationMapper.selectDetailedById(23L)).thenReturn(crossDept);
        assertThatThrownBy(() -> f.service.detailForDoctor(8L, 23L))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
        // 其他医生进行中的问诊单不可见
        OnlineConsultation otherDoctor = f.consultation("IN_PROGRESS");
        otherDoctor.setId(24L);
        otherDoctor.setDoctorId(88L);
        when(f.consultationMapper.selectDetailedById(24L)).thenReturn(otherDoctor);
        assertThatThrownBy(() -> f.service.detailForDoctor(8L, 24L))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
        // 查看不推进状态：读取路径不触发接受/完成等状态写入；入口惰性收敛是规格要求的统一行为
        verify(f.consultationMapper, atLeastOnce()).expireOverdue("WAITING_DOCTOR", "EXPIRED");
        verify(f.consultationMapper, never()).accept(anyLong(), anyLong(), anyString(), anyString());
        verify(f.consultationMapper, never())
                .complete(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void doctorDetailSweepsExpiredPoolRowBeforeRead() {
        Fixture f = new Fixture();
        f.givenDoctor(8L, 3L);
        when(f.consultationMapper.selectStandardDepartmentIdByDoctor(3L)).thenReturn(2L);
        // 惰性收敛后的读取结果：过期待接诊单已是 EXPIRED，对医生不再可见
        OnlineConsultation expired = f.consultation("EXPIRED");
        expired.setId(25L);
        expired.setDoctorId(null);
        when(f.consultationMapper.selectDetailedById(25L)).thenReturn(expired);

        assertThatThrownBy(() -> f.service.detailForDoctor(8L, 25L))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
        InOrder order = inOrder(f.consultationMapper);
        order.verify(f.consultationMapper).expireOverdue("WAITING_DOCTOR", "EXPIRED");
        order.verify(f.consultationMapper).selectDetailedById(25L);
    }

    @Test
    void mineSweepsExpiredBeforeListing() {
        Fixture f = new Fixture();
        f.givenDoctor(8L, 3L);
        when(f.consultationMapper.selectMine(3L, "IN_PROGRESS")).thenReturn(List.of());

        assertThat(f.service.mine(8L, "IN_PROGRESS")).isEmpty();
        verify(f.consultationMapper).expireOverdue("WAITING_DOCTOR", "EXPIRED");
    }

    @Test
    void acceptBindsDoctorAndWritesSystemMessage() {
        Fixture f = new Fixture();
        f.givenDoctor(8L, 3L);
        OnlineConsultation waiting = f.consultation("WAITING_DOCTOR");
        OnlineConsultation inProgress = f.consultation("IN_PROGRESS");
        when(f.consultationMapper.selectDetailedById(21L)).thenReturn(waiting, inProgress);
        when(f.consultationMapper.selectStandardDepartmentIdByDoctor(3L)).thenReturn(2L);
        when(f.consultationMapper.accept(21L, 3L, "WAITING_DOCTOR", "IN_PROGRESS"))
                .thenReturn(1);
        when(f.allergyMapper.selectAllergens(3L)).thenReturn(List.of());

        OnlineConsultationService.DoctorConsultationDetail detail = f.service.accept(8L, 21L);

        assertThat(detail.status()).isEqualTo("IN_PROGRESS");
        ArgumentCaptor<OnlineConsultationMessage> message = ArgumentCaptor.forClass(OnlineConsultationMessage.class);
        verify(f.messageMapper).insert(message.capture());
        assertThat(message.getValue().getSenderType()).isEqualTo("SYSTEM");
        assertThat(message.getValue().getContent()).isEqualTo("医生已接受问诊");
        // 接受入口先惰性收敛过期单，再走原子条件更新
        verify(f.consultationMapper).expireOverdue("WAITING_DOCTOR", "EXPIRED");
    }

    @Test
    void acceptConflictWhenConditionalUpdateHitsZeroRows() {
        Fixture f = new Fixture();
        f.givenDoctor(8L, 3L);
        when(f.consultationMapper.selectDetailedById(21L)).thenReturn(f.consultation("WAITING_DOCTOR"));
        when(f.consultationMapper.selectStandardDepartmentIdByDoctor(3L)).thenReturn(2L);
        // 并发落败或单已过期：条件更新 0 行（过期 SQL 语义由 PG 集成测试覆盖）
        when(f.consultationMapper.accept(21L, 3L, "WAITING_DOCTOR", "IN_PROGRESS"))
                .thenReturn(0);

        assertThatThrownBy(() -> f.service.accept(8L, 21L)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(409);
            assertThat(e.getMessage()).isEqualTo("该问诊单已被其他医生接受");
        });
        verify(f.messageMapper, never()).insert(any(OnlineConsultationMessage.class));
    }

    @Test
    void acceptCrossDepartmentRejectedBeforeAnyWrite() {
        Fixture f = new Fixture();
        f.givenDoctor(8L, 3L);
        OnlineConsultation crossDept = f.consultation("WAITING_DOCTOR");
        crossDept.setStandardDepartmentId(9L);
        when(f.consultationMapper.selectDetailedById(21L)).thenReturn(crossDept);
        when(f.consultationMapper.selectStandardDepartmentIdByDoctor(3L)).thenReturn(2L);

        assertThatThrownBy(() -> f.service.accept(8L, 21L))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
        verify(f.consultationMapper, never()).expireOverdue(anyString(), anyString());
        verify(f.consultationMapper, never()).accept(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void nonDoctorStaffIsRejected() {
        Fixture f = new Fixture();
        StaffUser admin = new StaffUser();
        admin.setId(1L);
        admin.setRole(StaffUser.ROLE_ADMIN);
        when(f.staffUserMapper.selectById(1L)).thenReturn(admin);

        assertThatThrownBy(() -> f.service.pool(1L)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(403);
            assertThat(e.getMessage()).isEqualTo("仅医生可操作");
        });
    }

    // ------------------------------------------------------------------
    // B 端：发起方式、医患消息、完成
    // ------------------------------------------------------------------

    @Test
    void startMethodFirstTimePersistsAndNotifies() {
        Fixture f = new Fixture();
        f.givenDoctor(8L, 3L);
        OnlineConsultation inProgress = f.consultation("IN_PROGRESS");
        OnlineConsultation withVideo = f.consultation("IN_PROGRESS");
        withVideo.setConsultMethod("VIDEO");
        withVideo.setMethodStartedAt(OffsetDateTime.now());
        when(f.consultationMapper.selectDetailedById(21L)).thenReturn(inProgress, withVideo);
        when(f.consultationMapper.startMethod(21L, 3L, "IN_PROGRESS", "VIDEO")).thenReturn(1);
        when(f.allergyMapper.selectAllergens(3L)).thenReturn(List.of());

        OnlineConsultationService.DoctorConsultationDetail detail = f.service.startMethod(8L, 21L, "VIDEO");

        assertThat(detail.consultMethod()).isEqualTo("VIDEO");
        ArgumentCaptor<OnlineConsultationMessage> message = ArgumentCaptor.forClass(OnlineConsultationMessage.class);
        verify(f.messageMapper).insert(message.capture());
        assertThat(message.getValue().getContent()).isEqualTo("医生发起视频问诊（模拟）");
    }

    @Test
    void startMethodSameMethodIsIdempotentAndDifferentConflicts() {
        Fixture f = new Fixture();
        f.givenDoctor(8L, 3L);
        OnlineConsultation withVideo = f.consultation("IN_PROGRESS");
        withVideo.setConsultMethod("VIDEO");
        when(f.consultationMapper.selectDetailedById(21L)).thenReturn(withVideo);
        when(f.allergyMapper.selectAllergens(3L)).thenReturn(List.of());

        // 同方式重复发起：幂等返回，不再写库
        assertThat(f.service.startMethod(8L, 21L, "VIDEO").consultMethod()).isEqualTo("VIDEO");
        verify(f.consultationMapper, never()).startMethod(anyLong(), anyLong(), anyString(), anyString());
        // 换方式：明确冲突
        assertThatThrownBy(() -> f.service.startMethod(8L, 21L, "TEXT"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("接诊方式已发起，不可更换");
                });
    }

    @Test
    void startMethodRequiresInProgressAndKnownMethod() {
        Fixture f = new Fixture();
        f.givenDoctor(8L, 3L);
        // 绑定到当前医生、但状态仍停在待接诊：命中 409 而非归属 404
        OnlineConsultation waitingBound = f.consultation("WAITING_DOCTOR");
        waitingBound.setDoctorId(3L);
        when(f.consultationMapper.selectDetailedById(21L)).thenReturn(waitingBound);

        assertThatThrownBy(() -> f.service.startMethod(8L, 21L, "VIDEO"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("问诊不在进行中");
                });
        assertThatThrownBy(() -> f.service.startMethod(8L, 21L, "AUDIO"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void patientMessageRequiresOwnershipAndInProgress() {
        Fixture f = new Fixture();
        // 归属失败：他人问诊单 404
        when(f.consultationMapper.selectDetailedByIdAndPatient(21L, 12L)).thenReturn(null);
        assertThatThrownBy(() -> f.service.sendMessageForPatient(12L, 21L, "你好"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
        // 非进行中：409
        when(f.consultationMapper.selectDetailedByIdAndPatient(22L, 12L)).thenReturn(f.consultation("WAITING_DOCTOR"));
        assertThatThrownBy(() -> f.service.sendMessageForPatient(12L, 22L, "你好"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(409));
        // 进行中但医生尚未发起接诊方式：409 method_required（图文/视频都只能在接受后由医生发起）
        when(f.consultationMapper.selectDetailedByIdAndPatient(24L, 12L)).thenReturn(f.consultation("IN_PROGRESS"));
        assertThatThrownBy(() -> f.service.sendMessageForPatient(12L, 24L, "你好"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("医生尚未发起接诊方式");
                });
        // 进行中且已发起图文：落 PATIENT 消息
        OnlineConsultation initiated = f.consultation("IN_PROGRESS");
        initiated.setConsultMethod("TEXT");
        when(f.consultationMapper.selectDetailedByIdAndPatient(23L, 12L)).thenReturn(initiated);
        OnlineConsultationService.MessageView sent = f.service.sendMessageForPatient(12L, 23L, "医生你好");
        ArgumentCaptor<OnlineConsultationMessage> message = ArgumentCaptor.forClass(OnlineConsultationMessage.class);
        verify(f.messageMapper).insert(message.capture());
        assertThat(message.getValue().getSenderType()).isEqualTo("PATIENT");
        assertThat(sent.senderType()).isEqualTo("PATIENT");
    }

    @Test
    void doctorMessageRequiresBinding() {
        Fixture f = new Fixture();
        f.givenDoctor(8L, 3L);
        OnlineConsultation otherDoctor = f.consultation("IN_PROGRESS");
        otherDoctor.setDoctorId(88L);
        when(f.consultationMapper.selectDetailedById(21L)).thenReturn(otherDoctor);

        assertThatThrownBy(() -> f.service.sendMessageForDoctor(8L, 21L, "你好"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
        verify(f.messageMapper, never()).insert(any(OnlineConsultationMessage.class));
    }

    @Test
    void doctorMessageRequiresInitiatedMethod() {
        Fixture f = new Fixture();
        f.givenDoctor(8L, 3L);
        // 已绑定且进行中，但医生尚未发起图文/视频：409 method_required
        when(f.consultationMapper.selectDetailedById(21L)).thenReturn(f.consultation("IN_PROGRESS"));

        assertThatThrownBy(() -> f.service.sendMessageForDoctor(8L, 21L, "你好"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("医生尚未发起接诊方式");
                });
        verify(f.messageMapper, never()).insert(any(OnlineConsultationMessage.class));
    }

    @Test
    void completeWritesDiagnosisAdviceAndSystemMessage() {
        Fixture f = new Fixture();
        f.givenDoctor(8L, 3L);
        OnlineConsultation inProgress = f.consultation("IN_PROGRESS");
        OnlineConsultation completed = f.consultation("COMPLETED");
        when(f.consultationMapper.selectDetailedById(21L)).thenReturn(inProgress, completed);
        when(f.consultationMapper.complete(21L, 3L, "IN_PROGRESS", "COMPLETED", "急性上呼吸道感染", "清淡饮食"))
                .thenReturn(1);
        when(f.allergyMapper.selectAllergens(3L)).thenReturn(List.of());

        OnlineConsultationService.DoctorConsultationDetail detail = f.service.complete(8L, 21L, "急性上呼吸道感染", "清淡饮食");

        assertThat(detail.status()).isEqualTo("COMPLETED");
        ArgumentCaptor<OnlineConsultationMessage> message = ArgumentCaptor.forClass(OnlineConsultationMessage.class);
        verify(f.messageMapper).insert(message.capture());
        assertThat(message.getValue().getContent()).isEqualTo("问诊已完成");
    }

    @Test
    void completeIsIdempotentWhenAlreadyCompleted() {
        Fixture f = new Fixture();
        f.givenDoctor(8L, 3L);
        when(f.consultationMapper.selectDetailedById(21L)).thenReturn(f.consultation("COMPLETED"));
        when(f.allergyMapper.selectAllergens(3L)).thenReturn(List.of());

        OnlineConsultationService.DoctorConsultationDetail detail = f.service.complete(8L, 21L, "急性上呼吸道感染", "清淡饮食");

        assertThat(detail.status()).isEqualTo("COMPLETED");
        verify(f.consultationMapper, never())
                .complete(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    private static final class Fixture {
        private final OnlineConsultationMapper consultationMapper = mock(OnlineConsultationMapper.class);
        private final OnlineConsultationMessageMapper messageMapper = mock(OnlineConsultationMessageMapper.class);
        private final PreconsultationDraftMapper draftMapper = mock(PreconsultationDraftMapper.class);
        private final StaffUserMapper staffUserMapper = mock(StaffUserMapper.class);
        private final HealthProfileAllergyMapper allergyMapper = mock(HealthProfileAllergyMapper.class);
        private final OnlineConsultationService service;

        private Fixture() {
            when(consultationMapper.insert(any(OnlineConsultation.class))).thenAnswer(invocation -> {
                OnlineConsultation consultation = invocation.getArgument(0);
                if (consultation.getId() == null) {
                    consultation.setId(consultation.getDraftId() == 5L ? 21L : 22L);
                }
                return 1;
            });
            when(messageMapper.insert(any(OnlineConsultationMessage.class))).thenAnswer(invocation -> {
                OnlineConsultationMessage message = invocation.getArgument(0);
                message.setId(99L);
                message.setCreatedAt(OffsetDateTime.parse("2026-08-07T10:06:00+08:00"));
                return 1;
            });
            service = new OnlineConsultationService(
                    consultationMapper,
                    messageMapper,
                    draftMapper,
                    staffUserMapper,
                    allergyMapper,
                    directTransaction(),
                    TestContracts.instance(),
                    Mappers.getMapper(OnlineConsultationDtoMapper.class));
        }

        private void givenDoctor(long staffId, long doctorId) {
            StaffUser staff = new StaffUser();
            staff.setId(staffId);
            staff.setRole(StaffUser.ROLE_DOCTOR);
            staff.setDoctorId(doctorId);
            when(staffUserMapper.selectById(staffId)).thenReturn(staff);
        }

        private PreconsultationDraft draft(String status) {
            PreconsultationDraft draft = new PreconsultationDraft();
            draft.setId(5L);
            draft.setPatientId(12L);
            draft.setHealthProfileId(3L);
            draft.setConversationId(77L);
            draft.setStatus(status);
            draft.setChiefComplaint("咳嗽三天");
            draft.setPresentIllness("干咳无痰");
            draft.setAllergyHistory("无");
            draft.setSummaryDisclaimer("仅供参考，不替代医生诊断");
            draft.setSuggestedStandardDepartmentId(2L);
            draft.setSummaryUpdatedAt(OffsetDateTime.parse("2026-08-07T09:30:00+08:00"));
            return draft;
        }

        private OnlineConsultation consultation(String status) {
            OnlineConsultation consultation = new OnlineConsultation();
            consultation.setId(21L);
            consultation.setPatientId(12L);
            consultation.setHealthProfileId(3L);
            consultation.setDraftId(5L);
            consultation.setConversationId(77L);
            consultation.setStandardDepartmentId(2L);
            consultation.setStandardDepartmentName("呼吸内科");
            consultation.setChiefComplaint("咳嗽三天");
            consultation.setPresentIllness("干咳无痰");
            consultation.setAllergyHistory("无");
            consultation.setSummaryDisclaimer("仅供参考，不替代医生诊断");
            consultation.setStatus(status);
            consultation.setExpiresAt(OffsetDateTime.now().plusSeconds(600));
            if (!"WAITING_DOCTOR".equals(status)) {
                consultation.setDoctorId(3L);
                consultation.setAcceptedAt(OffsetDateTime.parse("2026-08-07T10:03:00+08:00"));
            }
            return consultation;
        }

        /** 单测直接同步执行事务回调；线程竞争语义由 OnlineConsultationConcurrencyTest 覆盖。 */
        private TransactionTemplate directTransaction() {
            TransactionTemplate template = mock(TransactionTemplate.class);
            when(template.execute(any())).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(mock(TransactionStatus.class));
            });
            return template;
        }
    }
}
