package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.entity.chat.ChatRound;
import com.zhiyu.health.rule.RedFlagRuleEngine;
import com.zhiyu.health.service.chat.AgentCallLogService;
import com.zhiyu.health.service.chat.ChatRoundPersistence;
import com.zhiyu.health.service.chat.ChatRoundService;
import com.zhiyu.health.service.chat.PreconsultationService;
import com.zhiyu.health.service.health.HealthProfileService;
import com.zhiyu.health.support.TestContracts;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

/**
 * 通用药品说明书流轮次（票 51，ADR-0028）：chat 信封 medication_name 经 server-py 流式透传，
 * 轮次落 KIND_TEXT；不调健康档案/Neo4j/规则引擎（C 端不做个性化禁忌判定）。
 */
class MedicationRoundServiceTest {

    @Test
    void medicationRoundRelaysTokensAppendsConsultWordingAndCompletes() throws Exception {
        // server-py 已在流尾注入免责声明：java 只追加 consult_professional 话术
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round();
        when(fixture.persistence.find(12L, "req-med-1")).thenReturn(null);
        when(fixture.persistence.create(12L, "req-med-1", null, "阿莫西林胶囊")).thenReturn(round);
        ChatRoundService.Handle handle = fixture.service.acceptMedication(fixture.command("req-med-1"));

        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder("{\"text\":\"【用途】\"}").event("token").build());
        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder("{\"text\":\"抗感染。\"}").event("token").build());
        fixture.upstream.tryEmitNext(ServerSentEvent.builder("{\"text\":\"\\n\\n仅供参考，不替代医生诊断\"}")
                .event("token")
                .build());
        fixture.upstream.tryEmitNext(ServerSentEvent.builder("{}").event("done").build());
        fixture.upstream.tryEmitComplete();

        List<ChatRoundService.Event> observed = handle.events().collectList().block(Duration.ofSeconds(1));
        List<String> events =
                observed.stream().map(ChatRoundService.Event::event).toList();
        // meta → token×3 → 流尾 consult 话术 token → message → done
        assertThat(events).containsExactly("meta", "token", "token", "token", "token", "message", "done");
        // 流尾双话术：免责声明（server-py 注入透传）+ consult_professional（java 出口兜底追加）
        ChatRoundService.Event consultToken = observed.get(4);
        assertThat(consultToken.data().path("text").asText()).contains("具体是否适用请咨询医生或药师");
        ChatRoundService.Event message = observed.get(5);
        String content = message.data().path("content").asText();
        assertThat(content).contains("【用途】").contains("仅供参考，不替代医生诊断");
        assertThat(content).endsWith("具体是否适用请咨询医生或药师");
        assertThat(message.data().path("disclaimer").asText()).isEqualTo("仅供参考，不替代医生诊断");
        // 轮次落 KIND_TEXT（经 message 事件持久化边界）
        verify(fixture.persistence).persistEvent(eq(round), eq("message"), any());
        verify(fixture.persistence).markCompleted(34L);
        // C 端说明书流零依赖：健康档案/规则引擎不被调用（不做个性化禁忌判定）
        verify(fixture.healthProfiles, never()).agentContext(org.mockito.ArgumentMatchers.anyLong());
        verify(fixture.redFlagRules, never()).judge(anyString());
    }

    @Test
    void missingDisclaimerIsBackedOffAtExit() throws Exception {
        // server-py 未注入免责声明时，java 出口兜底补齐双话术（硬约束 1）
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round();
        when(fixture.persistence.find(12L, "req-med-2")).thenReturn(null);
        when(fixture.persistence.create(eq(12L), eq("req-med-2"), org.mockito.ArgumentMatchers.isNull(), anyString()))
                .thenReturn(round);
        ChatRoundService.Handle handle = fixture.service.acceptMedication(fixture.command("req-med-2"));

        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder("{\"text\":\"【用途】退热。\"}").event("token").build());
        fixture.upstream.tryEmitNext(ServerSentEvent.builder("{}").event("done").build());
        fixture.upstream.tryEmitComplete();

        List<ChatRoundService.Event> observed = handle.events().collectList().block(Duration.ofSeconds(1));
        // meta → token → 兜底免责 token → consult token → message → done
        assertThat(observed.stream().map(ChatRoundService.Event::event).toList())
                .containsExactly("meta", "token", "token", "token", "message", "done");
        String content = observed.get(4).data().path("content").asText();
        assertThat(content).contains("【用途】退热。");
        assertThat(content).contains("仅供参考，不替代医生诊断");
        assertThat(content).endsWith("具体是否适用请咨询医生或药师");
    }

    @Test
    void duplicateMedicationRequestObservesSameRoundWithoutSecondAgentCall() {
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round();
        when(fixture.persistence.find(12L, "req-med-dup")).thenReturn(null, round);
        when(fixture.persistence.create(eq(12L), eq("req-med-dup"), org.mockito.ArgumentMatchers.isNull(), anyString()))
                .thenReturn(round);

        ChatRoundService.Handle first = fixture.service.acceptMedication(fixture.command("req-med-dup"));
        ChatRoundService.Handle duplicate = fixture.service.acceptMedication(fixture.command("req-med-dup"));

        assertThat(duplicate.conversationId()).isEqualTo(first.conversationId());
        verify(fixture.agentClient).medicationKnowledge(anyString());
    }

    @Test
    void upstreamFailureMarksRoundFailed() {
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round();
        when(fixture.persistence.find(12L, "req-med-fail")).thenReturn(null);
        when(fixture.persistence.create(
                        eq(12L), eq("req-med-fail"), org.mockito.ArgumentMatchers.isNull(), anyString()))
                .thenReturn(round);
        ChatRoundService.Handle handle = fixture.service.acceptMedication(fixture.command("req-med-fail"));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        handle.events().subscribe(event -> {}, failure::set);
        fixture.upstream.tryEmitError(new IllegalStateException("connection reset"));

        assertThat(failure.get()).isInstanceOf(ChatRoundService.RoundFailedException.class);
        verify(fixture.persistence).markFailed(34L, "AGENT_FAILED");
        verify(fixture.persistence, never()).markCompleted(any());
    }

    @Test
    void blankDrugNameIsRejected() {
        Fixture fixture = new Fixture();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.service.acceptMedication(
                        new ChatRoundService.MedicationCommand(12L, "req-med-blank", null, "  ")))
                .isInstanceOf(com.zhiyu.health.config.ApiException.class);
    }

    private static final class Fixture {
        private final AgentClient agentClient = mock(AgentClient.class);
        private final ChatRoundPersistence persistence = mock(ChatRoundPersistence.class);
        private final HealthProfileService healthProfiles = mock(HealthProfileService.class);
        private final RedFlagRuleEngine redFlagRules = mock(RedFlagRuleEngine.class);
        private final AgentCallLogService agentCallLogs = mock(AgentCallLogService.class);
        private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        private final ObjectMapper mapper = new ObjectMapper();
        private final Sinks.Many<ServerSentEvent<String>> upstream =
                Sinks.many().replay().all();
        private final ChatRoundService service;

        private Fixture() {
            when(agentClient.medicationKnowledge(anyString())).thenReturn(upstream.asFlux());
            when(persistence.persistEvent(any(), anyString(), any())).thenAnswer(invocation -> {
                JsonNode data = invocation.getArgument(2);
                // 模拟持久化边界的免责挂载与 message_id 回填
                ((com.fasterxml.jackson.databind.node.ObjectNode) data)
                        .put("message_id", 99L)
                        .put("disclaimer", "仅供参考，不替代医生诊断");
                return data;
            });
            service = new ChatRoundService(
                    agentClient,
                    persistence,
                    redFlagRules,
                    mapper,
                    TestContracts.instance(),
                    healthProfiles,
                    agentCallLogs,
                    redis,
                    mock(PreconsultationService.class));
        }

        private ChatRound round() {
            ChatRound round = new ChatRound(12L, "req-med", 7L, 9L, "ACCEPTED");
            round.setId(34L);
            return round;
        }

        private ChatRoundService.MedicationCommand command(String requestId) {
            return new ChatRoundService.MedicationCommand(12L, requestId, null, "阿莫西林胶囊");
        }
    }
}
