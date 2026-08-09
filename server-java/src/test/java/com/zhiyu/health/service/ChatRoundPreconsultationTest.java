package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.entity.ChatRound;
import com.zhiyu.health.entity.PreconsultationDraft;
import com.zhiyu.health.rule.RedFlagRuleEngine;
import com.zhiyu.health.support.TestContracts;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

/** 票 55 可信预问诊模式：草稿归属/状态校验、场景强制、锁定档案注入、会话回填与摘要快照旁路。 */
class ChatRoundPreconsultationTest {

    @Test
    @SuppressWarnings("unchecked")
    void draftForcesScenarioAndLockedProfileAndDraftConversation() {
        Fixture fixture = new Fixture();
        PreconsultationDraft draft = fixture.draft(5L, 77L, 3L);
        when(fixture.preconsultationService.requireForChat(12L, 5L)).thenReturn(draft);
        ChatRound round = fixture.round("ACCEPTED", 77L);
        when(fixture.persistence.find(12L, "req-pre")).thenReturn(null);
        when(fixture.persistence.create(12L, "req-pre", 77L, "我咳嗽三天了")).thenReturn(round);
        HealthProfileService.AgentProfileContext lockedProfile = new HealthProfileService.AgentProfileContext(
                3L, "小愈", "女", LocalDate.parse("1990-01-01"), "本人", List.of());
        when(fixture.healthProfiles.agentContext(12L, 3L)).thenReturn(lockedProfile);

        // 请求体 scenario 伪造 triage 也必须被草稿强制为 preconsultation
        fixture.service.accept(fixture.command("req-pre", 5L, "triage"));

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(fixture.agentClient).chat(body.capture());
        assertThat(body.getValue())
                .containsEntry("scenario", "preconsultation")
                .containsEntry("conversation_id", 77L)
                .containsEntry("health_profile", lockedProfile)
                .containsEntry("preconsultation_draft_id", 5L);
        // 锁定档案走指定档案通道，不走当前激活档案通道
        verify(fixture.healthProfiles, never()).agentContext(12L);
        // 草稿已绑定会话：不再回填
        verify(fixture.preconsultationService, never()).attachConversation(anyLong(), anyLong());
    }

    @Test
    void firstRoundLazilyCreatesConversationAndAttachesItToDraft() {
        Fixture fixture = new Fixture();
        PreconsultationDraft draft = fixture.draft(5L, null, 3L);
        when(fixture.preconsultationService.requireForChat(12L, 5L)).thenReturn(draft);
        ChatRound round = fixture.round("ACCEPTED", 90L);
        when(fixture.persistence.find(12L, "req-first")).thenReturn(null);
        when(fixture.persistence.create(12L, "req-first", null, "我咳嗽三天了")).thenReturn(round);

        fixture.service.accept(fixture.command("req-first", 5L, null));

        verify(fixture.preconsultationService).attachConversation(5L, 90L);
    }

    @Test
    void barePreconsultationScenarioWithoutDraftIsRejected() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.accept(fixture.command("req-bare", null, "preconsultation")))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(409);
                    assertThat(error.getMessage()).isEqualTo("预问诊场景需要有效的预问诊草稿");
                });
        verify(fixture.persistence, never()).create(any(), anyString(), any(), anyString());
        verify(fixture.agentClient, never()).chat(any());
    }

    @Test
    void foreignOrSubmittedDraftIsRejectedBeforeRoundCreation() {
        Fixture fixture = new Fixture();
        when(fixture.preconsultationService.requireForChat(12L, 5L))
                .thenThrow(new ApiException(409, "预问诊场景需要有效的预问诊草稿"));

        assertThatThrownBy(() -> fixture.service.accept(fixture.command("req-foreign", 5L, null)))
                .isInstanceOf(ApiException.class);
        verify(fixture.persistence, never()).create(any(), anyString(), any(), anyString());
        verify(fixture.agentClient, never()).chat(any());
    }

    @Test
    void messageEventNoLongerCarriesSummary_draftIdForwardedForAsyncCallback() {
        // 票 55 改造：摘要不再随 message 事件下发，改为 server-py 后台异步回调
        // /api/agent/preconsultation-drafts/{id}/summary 落草稿。故 message 事件即使
        // 携带 preconsultation_summary 字段（旧客户端/回归），forward 也不再旁路触发 applySummary。
        Fixture fixture = new Fixture();
        fixture.startPreconsultRound("req-summary");
        String payload =
                """
                {"role":"assistant","content":"已更新摘要","preconsultation_summary":{"chief_complaint":"咳嗽三天","present_illness":"干咳无痰","allergy_history":"无","suggested_standard_department_id":2}}
                """;

        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder(payload).event("message").build());

        // message 事件不再旁路触发摘要落库（改为独立回调端点）
        verify(fixture.preconsultationService, never()).applySummary(anyLong(), any());
    }

    @Test
    void roundCompletesOnMessageAndDoneWithoutSummarySideEffect() {
        // 票 55 改造：摘要旁路已移除，message+done 正常流转不再触达 preconsultationService。
        // 摘要落库改由独立回调端点（PreconsultationSummaryCallbackController）负责。
        Fixture fixture = new Fixture();
        fixture.startPreconsultRound("req-summary-fail");

        fixture.upstream.tryEmitNext(ServerSentEvent.builder(
                        "{\"role\":\"assistant\",\"content\":\"x\",\"preconsultation_summary\":{\"chief_complaint\":\"咳嗽\",\"present_illness\":\"干咳\"}}")
                .event("message")
                .build());
        fixture.upstream.tryEmitNext(ServerSentEvent.builder("{}").event("done").build());
        fixture.upstream.tryEmitComplete();

        List<String> observed = fixture.handle
                .events()
                .map(ChatRoundService.Event::event)
                .collectList()
                .block(Duration.ofSeconds(1));
        assertThat(observed).containsExactly("message", "done");
        verify(fixture.persistence).markCompleted(34L);
        verify(fixture.persistence, never()).markFailed(any(), anyString());
        // message 事件含旧字段也不再触发 applySummary（旁路已移除）
        verify(fixture.preconsultationService, never()).applySummary(anyLong(), any());
    }

    @Test
    void messageEventWithoutSummaryFieldLeavesDraftUntouched() {
        Fixture fixture = new Fixture();
        fixture.startPreconsultRound("req-no-summary");

        fixture.upstream.tryEmitNext(ServerSentEvent.builder("{\"role\":\"assistant\",\"content\":\"继续问一个问题\"}")
                .event("message")
                .build());

        verify(fixture.preconsultationService, never()).applySummary(anyLong(), any());
    }

    @Test
    void redFlagRoundNeverTouchesDraftSummary() {
        Fixture fixture = new Fixture();
        PreconsultationDraft draft = fixture.draft(5L, 77L, 3L);
        when(fixture.preconsultationService.requireForChat(12L, 5L)).thenReturn(draft);
        ChatRound round = fixture.round("ACCEPTED", 77L);
        when(fixture.persistence.find(12L, "req-red")).thenReturn(null);
        when(fixture.persistence.create(12L, "req-red", 77L, "我突然昏迷了")).thenReturn(round);
        com.zhiyu.health.entity.Message warning = new com.zhiyu.health.entity.Message();
        warning.setId(41L);
        when(fixture.persistence.completeRedFlag(eq(round), anyString())).thenReturn(warning);

        fixture.service.accept(new ChatRoundService.Command(
                12L, "req-red", null, "我突然昏迷了", null, null, null, null, null, null, 5L, null));

        // 红线轮次不调 Agent、不更新摘要快照；草稿只承担场景与档案绑定
        verify(fixture.agentClient, never()).chat(any());
        verify(fixture.preconsultationService, never()).applySummary(anyLong(), any());
    }

    private static final class Fixture {
        private final AgentClient agentClient = mock(AgentClient.class);
        private final ChatRoundPersistence persistence = mock(ChatRoundPersistence.class);
        private final HealthProfileService healthProfiles = mock(HealthProfileService.class);
        private final AgentCallLogService agentCallLogs = mock(AgentCallLogService.class);
        private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        private final PreconsultationService preconsultationService = mock(PreconsultationService.class);
        private final ObjectMapper mapper = new ObjectMapper();
        private final Sinks.Many<ServerSentEvent<String>> upstream =
                Sinks.many().replay().all();
        private final ChatRoundService service;
        private ChatRoundService.Handle handle;

        private Fixture() {
            when(agentClient.chat(any())).thenReturn(upstream.asFlux());
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null);
            when(persistence.recentContext(77L)).thenReturn(List.of(Map.of("role", "user", "content", "你好")));
            when(persistence.persistEvent(any(), anyString(), any()))
                    .thenAnswer(invocation -> invocation.getArgument(2));
            service = new ChatRoundService(
                    agentClient,
                    persistence,
                    new RedFlagRuleEngine(),
                    mapper,
                    TestContracts.instance(),
                    healthProfiles,
                    agentCallLogs,
                    redis,
                    preconsultationService);
        }

        private PreconsultationDraft draft(Long id, Long conversationId, Long healthProfileId) {
            PreconsultationDraft draft = new PreconsultationDraft();
            draft.setId(id);
            draft.setPatientId(12L);
            draft.setConversationId(conversationId);
            draft.setHealthProfileId(healthProfileId);
            draft.setStatus("PENDING_CONFIRM");
            return draft;
        }

        private ChatRound round(String status, Long conversationId) {
            ChatRound round = new ChatRound(12L, "req", conversationId, 9L, status);
            round.setId(34L);
            return round;
        }

        private ChatRoundService.Command command(String requestId, Long draftId, String scenario) {
            return new ChatRoundService.Command(
                    12L, requestId, null, "我咳嗽三天了", null, scenario, null, null, null, null, draftId, null);
        }

        private void startPreconsultRound(String requestId) {
            PreconsultationDraft draft = draft(5L, 77L, 3L);
            when(preconsultationService.requireForChat(12L, 5L)).thenReturn(draft);
            ChatRound round = round("ACCEPTED", 77L);
            when(persistence.find(12L, requestId)).thenReturn(null);
            when(persistence.create(12L, requestId, 77L, "我咳嗽三天了")).thenReturn(round);
            handle = service.accept(command(requestId, 5L, null));
        }
    }
}
