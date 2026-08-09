package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.chat.PreconsultationDraft;
import com.zhiyu.health.mapper.chat.PreconsultationDraftMapper;
import com.zhiyu.health.mapper.consultation.OnlineConsultationMapper;
import com.zhiyu.health.mapper.organization.StandardDepartmentMapper;
import com.zhiyu.health.service.chat.PreconsultationService;
import com.zhiyu.health.service.common.DisclaimerService;
import com.zhiyu.health.service.consultation.mapping.PreconsultationDtoMapper;
import com.zhiyu.health.service.health.HealthProfileService;
import org.junit.jupiter.api.Test;

class PreconsultationServiceTest {

    @Test
    void abandonMovesCollectingDraftToTerminalState() {
        Fixture fixture = new Fixture("COLLECTING");
        when(fixture.mapper.abandon(5L, 1L, "COLLECTING", "PENDING_CONFIRM", "ABANDONED"))
                .thenReturn(1);
        PreconsultationDraft abandoned = fixture.draft("ABANDONED");
        when(fixture.mapper.selectById(5L)).thenReturn(fixture.draft("COLLECTING"), abandoned);

        PreconsultationService.DraftView result = fixture.service.abandon(1L, 5L);

        assertThat(result.status()).isEqualTo("ABANDONED");
    }

    @Test
    void submittedDraftCannotBeAbandoned() {
        Fixture fixture = new Fixture("SUBMITTED");
        when(fixture.mapper.selectById(5L)).thenReturn(fixture.draft("SUBMITTED"));

        assertThatThrownBy(() -> fixture.service.abandon(1L, 5L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("本次预问诊已放弃");
    }

    private static final class Fixture {
        private final PreconsultationDraftMapper mapper = mock(PreconsultationDraftMapper.class);
        private final PreconsultationDtoMapper dtoMapper = mock(PreconsultationDtoMapper.class);
        private final Contracts contracts = Contracts.load(Contracts.resolveDir());
        private final PreconsultationService service;

        private Fixture(String initialStatus) {
            service = new PreconsultationService(
                    mapper,
                    mock(OnlineConsultationMapper.class),
                    mock(StandardDepartmentMapper.class),
                    mock(HealthProfileService.class),
                    mock(DisclaimerService.class),
                    contracts,
                    dtoMapper);
            when(dtoMapper.toView(any(), any(), any(), eq(null))).thenAnswer(invocation -> {
                PreconsultationDraft draft = invocation.getArgument(0);
                return new PreconsultationService.DraftView(
                        draft.getId(),
                        draft.getStatus(),
                        contracts.onlineConsultation().draftStatusLabels().get(draft.getStatus()),
                        draft.getConversationId(),
                        draft.getHealthProfileId(),
                        null,
                        null,
                        null);
            });
        }

        private PreconsultationDraft draft(String status) {
            PreconsultationDraft draft = new PreconsultationDraft();
            draft.setId(5L);
            draft.setPatientId(1L);
            draft.setHealthProfileId(2L);
            draft.setConversationId(9L);
            draft.setStatus(status);
            return draft;
        }
    }
}
