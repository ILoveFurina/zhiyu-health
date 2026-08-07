package com.zhiyu.health.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.entity.ChatRound;
import com.zhiyu.health.rule.RedFlagRuleEngine;
import com.zhiyu.health.support.TestContracts;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

/** 对话轮次公开业务接口：幂等、实时 token、断连后继续持久化。 */
class ChatRoundServiceTest {

    @Test
    void duplicateRequestObservesSameRunningRoundWithoutCallingAgentAgain() {
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round("RUNNING");
        when(fixture.persistence.find(12L, "req-1")).thenReturn(null, round);
        when(fixture.persistence.create(12L, "req-1", null, "你好")).thenReturn(round);

        ChatRoundService.Handle first = fixture.service.accept(fixture.command("req-1"));
        ChatRoundService.Handle duplicate = fixture.service.accept(fixture.command("req-1"));

        assertThat(duplicate.conversationId()).isEqualTo(first.conversationId());
        verify(fixture.agentClient).chat(any());
    }

    @Test
    void disposingObserverDoesNotCancelRoundAndFinalMessageIsPersisted() throws Exception {
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round("ACCEPTED");
        when(fixture.persistence.find(12L, "req-2")).thenReturn(null);
        when(fixture.persistence.create(12L, "req-2", null, "你好")).thenReturn(round);
        ChatRoundService.Handle handle = fixture.service.accept(fixture.command("req-2"));
        var observer = handle.events().subscribe();

        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder("{\"text\":\"你\"}").event("token").build());
        observer.dispose();
        fixture.upstream.tryEmitNext(ServerSentEvent.builder("{\"content\":\"你好\",\"effort\":\"disabled\"}")
                .event("message")
                .build());
        fixture.upstream.tryEmitNext(ServerSentEvent.builder("{}").event("done").build());
        fixture.upstream.tryEmitComplete();

        verify(fixture.persistence)
                .persistEvent(
                        round, "message", fixture.mapper.readTree("{\"content\":\"你好\",\"effort\":\"disabled\"}"));
        verify(fixture.persistence).markCompleted(34L);
        verify(fixture.persistence, never()).markFailed(any(), anyString());
    }

    @Test
    void tokenIsPublishedAsSoonAsAgentEmitsIt() {
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round("ACCEPTED");
        when(fixture.persistence.find(12L, "req-3")).thenReturn(null);
        when(fixture.persistence.create(12L, "req-3", null, "你好")).thenReturn(round);
        ChatRoundService.Handle handle = fixture.service.accept(fixture.command("req-3"));

        var future = handle.events()
                .filter(event -> "token".equals(event.event()))
                .next()
                .toFuture();
        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder("{\"text\":\"你\"}").event("token").build());

        assertThat(future).succeedsWithin(Duration.ofMillis(100));
    }

    /** 票 33 回归：多卡片长对话按序到达观察者，且每张卡片都经持久化边界落库。 */
    @Test
    void longConversationCardsStreamInOrderAndEachIsPersisted() {
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round("ACCEPTED");
        when(fixture.persistence.find(12L, "req-cards")).thenReturn(null);
        when(fixture.persistence.create(12L, "req-cards", null, "你好")).thenReturn(round);
        ChatRoundService.Handle handle = fixture.service.accept(fixture.command("req-cards"));

        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder("{\"effort\":\"quick\"}").event("meta").build());
        fixture.upstream.tryEmitNext(ServerSentEvent.builder("{\"doctors\":[{\"doctor_id\":1,\"name\":\"林知远\"}]}")
                .event("doctor_recommendations")
                .build());
        fixture.upstream.tryEmitNext(ServerSentEvent.builder("{\"doctor_id\":1,\"slots\":[{\"schedule_id\":9}]}")
                .event("doctor_slots")
                .build());
        fixture.upstream.tryEmitNext(ServerSentEvent.builder("{\"appointment_id\":21}")
                .event("appointment")
                .build());
        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder("{\"text\":\"已为\"}").event("token").build());
        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder("{\"text\":\"你挂号\"}").event("token").build());
        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder("{\"role\":\"assistant\",\"content\":\"已为你挂号\",\"effort\":\"quick\"}")
                        .event("message")
                        .build());
        fixture.upstream.tryEmitNext(ServerSentEvent.builder("{}").event("done").build());
        fixture.upstream.tryEmitComplete();

        List<String> observed =
                handle.events().map(ChatRoundService.Event::event).collectList().block(Duration.ofSeconds(1));
        assertThat(observed)
                .containsExactly(
                        "meta",
                        "doctor_recommendations",
                        "doctor_slots",
                        "appointment",
                        "token",
                        "token",
                        "message",
                        "done");
        InOrder order = inOrder(fixture.persistence);
        order.verify(fixture.persistence).persistEvent(eq(round), eq("meta"), any());
        order.verify(fixture.persistence).persistEvent(eq(round), eq("doctor_recommendations"), any());
        order.verify(fixture.persistence).persistEvent(eq(round), eq("doctor_slots"), any());
        order.verify(fixture.persistence).persistEvent(eq(round), eq("appointment"), any());
        order.verify(fixture.persistence).markCompleted(34L);
    }

    /** 票 33 主根因回归：卡片落库失败不再悬空掐流——轮次显式失败，观察者保留已发事件并收到错误信号。 */
    @Test
    void cardPersistenceFailureMarksRoundFailedAndSignalsObserver() {
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round("ACCEPTED");
        when(fixture.persistence.find(12L, "req-fail")).thenReturn(null);
        when(fixture.persistence.create(12L, "req-fail", null, "你好")).thenReturn(round);
        // 模拟票 33 原始故障：卡片落库抛数据访问异常（原样穿透曾掐断整条流）
        when(fixture.persistence.persistEvent(eq(round), eq("doctor_recommendations"), any()))
                .thenThrow(new DataIntegrityViolationException("value too long for type character varying(20)"));
        ChatRoundService.Handle handle = fixture.service.accept(fixture.command("req-fail"));

        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder("{\"effort\":\"quick\"}").event("meta").build());
        fixture.upstream.tryEmitNext(ServerSentEvent.builder("{\"doctors\":[{\"doctor_id\":1}]}")
                .event("doctor_recommendations")
                .build());
        // 失败后的上游迟到事件不得再触发落库
        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder("{\"text\":\"你\"}").event("token").build());
        fixture.upstream.tryEmitComplete();

        List<ChatRoundService.Event> received = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        handle.events().subscribe(received::add, failure::set);

        assertThat(received).extracting(ChatRoundService.Event::event).containsExactly("meta");
        assertThat(failure.get()).isInstanceOf(ChatRoundService.RoundFailedException.class);
        verify(fixture.persistence).markFailed(34L, "AGENT_FAILED");
        verify(fixture.persistence, never()).markCompleted(any());
        verify(fixture.persistence, never()).persistEvent(any(), eq("token"), any());
    }

    /** 票 24：trace 事件经独立可失败路径落库，不进 persistEvent 事务、不连坐主对话流。 */
    @Test
    void traceEventsAreAppendedAndEmittedWithoutPersistEvent() throws Exception {
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round("ACCEPTED");
        when(fixture.persistence.find(12L, "req-trace")).thenReturn(null);
        when(fixture.persistence.create(12L, "req-trace", null, "你好")).thenReturn(round);
        ChatRoundService.Handle handle = fixture.service.accept(fixture.command("req-trace"));

        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder("{\"effort\":\"quick\"}").event("meta").build());
        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder("{\"tool_call_id\":\"call-1\",\"tool_name\":\"recommend_doctors\"}")
                        .event("tool_start")
                        .build());
        fixture.upstream.tryEmitNext(ServerSentEvent.builder(
                        "{\"tool_call_id\":\"call-1\",\"tool_name\":\"recommend_doctors\",\"result\":\"success\"}")
                .event("tool_end")
                .build());
        fixture.upstream.tryEmitNext(ServerSentEvent.builder("{}").event("done").build());
        fixture.upstream.tryEmitComplete();

        List<String> observed =
                handle.events().map(ChatRoundService.Event::event).collectList().block(Duration.ofSeconds(1));
        assertThat(observed).containsExactly("meta", "tool_start", "tool_end", "done");
        // trace 事件不进 persistEvent（不走 messages 落库事务）
        verify(fixture.persistence, never()).persistEvent(eq(round), eq("tool_start"), any());
        verify(fixture.persistence, never()).persistEvent(eq(round), eq("tool_end"), any());
        // trace 经 AgentCallLogService 独立落库
        verify(fixture.agentCallLogs)
                .append(
                        any(),
                        eq("tool_start"),
                        eq(fixture.mapper.readTree(
                                "{\"tool_call_id\":\"call-1\",\"tool_name\":\"recommend_doctors\"}")));
        verify(fixture.agentCallLogs)
                .append(
                        any(),
                        eq("tool_end"),
                        eq(
                                fixture.mapper.readTree(
                                        "{\"tool_call_id\":\"call-1\",\"tool_name\":\"recommend_doctors\",\"result\":\"success\"}")));
        // 主对话流仍正常 markCompleted
        verify(fixture.persistence).markCompleted(34L);
    }

    /** 票 24 / ADR-0017：trace 落库失败只 log.warn，主流程仍 emit + markCompleted，不写 error_code。 */
    @Test
    void traceAppendFailureDoesNotBreakMainStream() {
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round("ACCEPTED");
        when(fixture.persistence.find(12L, "req-trace-fail")).thenReturn(null);
        when(fixture.persistence.create(12L, "req-trace-fail", null, "你好")).thenReturn(round);
        // 模拟 trace 落库异常：不得连坐主对话流
        doThrow(new DataIntegrityViolationException("connection lost"))
                .when(fixture.agentCallLogs)
                .append(any(), anyString(), any());
        ChatRoundService.Handle handle = fixture.service.accept(fixture.command("req-trace-fail"));

        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder("{\"effort\":\"quick\"}").event("meta").build());
        fixture.upstream.tryEmitNext(
                ServerSentEvent.builder("{\"tool_call_id\":\"call-1\",\"tool_name\":\"recommend_doctors\"}")
                        .event("tool_start")
                        .build());
        fixture.upstream.tryEmitNext(ServerSentEvent.builder("{}").event("done").build());
        fixture.upstream.tryEmitComplete();

        List<String> observed =
                handle.events().map(ChatRoundService.Event::event).collectList().block(Duration.ofSeconds(1));
        // trace 事件仍透传给 C 端，主对话流正常完成
        assertThat(observed).containsExactly("meta", "tool_start", "done");
        verify(fixture.persistence).markCompleted(34L);
        // 不写 chat_rounds.error_code（trace 落库失败不是轮次失败）
        verify(fixture.persistence, never()).markFailed(any(), anyString());
    }

    /** ADR-0021：请求未带 knowledge_source 时读 Redis 全局键补位透传给 server-py。 */
    @Test
    @SuppressWarnings("unchecked")
    void knowledgeSourceFilledFromRedisWhenRequestOmits() {
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round("ACCEPTED");
        when(fixture.persistence.find(12L, "req-ks-redis")).thenReturn(null);
        when(fixture.persistence.create(12L, "req-ks-redis", null, "你好")).thenReturn(round);
        when(fixture.valueOps.get("demo:knowledge_source")).thenReturn("graph");

        fixture.service.accept(fixture.command("req-ks-redis"));

        org.mockito.ArgumentCaptor<Map<String, Object>> body = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(fixture.agentClient).chat(body.capture());
        assertThat(body.getValue().get("knowledge_source")).isEqualTo("graph");
    }

    /** ADR-0021：请求与 Redis 全局键皆空时省略 body 字段，交 server-py 走 scenario 默认。 */
    @Test
    @SuppressWarnings("unchecked")
    void knowledgeSourceOmittedWhenBothRequestAndRedisEmpty() {
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round("ACCEPTED");
        when(fixture.persistence.find(12L, "req-ks-none")).thenReturn(null);
        when(fixture.persistence.create(12L, "req-ks-none", null, "你好")).thenReturn(round);

        fixture.service.accept(fixture.command("req-ks-none"));

        org.mockito.ArgumentCaptor<Map<String, Object>> body = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(fixture.agentClient).chat(body.capture());
        assertThat(body.getValue()).doesNotContainKey("knowledge_source");
    }

    /** 票 50：retry_standard_department_id 透传给 server-py；缺省时省略字段。 */
    @Test
    @SuppressWarnings("unchecked")
    void retryStandardDepartmentIdIsForwardedToAgentBody() {
        Fixture fixture = new Fixture();
        ChatRound round = fixture.round("ACCEPTED");
        when(fixture.persistence.find(12L, "req-retry")).thenReturn(null);
        when(fixture.persistence.create(12L, "req-retry", null, "重新查询号源")).thenReturn(round);
        ChatRoundService.Command retry =
                new ChatRoundService.Command(12L, "req-retry", null, "重新查询号源", "quick", "triage", null, null, null, 3L);

        fixture.service.accept(retry);

        org.mockito.ArgumentCaptor<Map<String, Object>> body = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(fixture.agentClient).chat(body.capture());
        assertThat(body.getValue()).containsEntry("retry_standard_department_id", 3L);

        // 缺省不透传：普通对话轮次 body 不含 retry 字段
        Fixture plain = new Fixture();
        ChatRound plainRound = plain.round("ACCEPTED");
        when(plain.persistence.find(12L, "req-plain")).thenReturn(null);
        when(plain.persistence.create(12L, "req-plain", null, "你好")).thenReturn(plainRound);
        plain.service.accept(plain.command("req-plain"));

        org.mockito.ArgumentCaptor<Map<String, Object>> plainBody = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(plain.agentClient).chat(plainBody.capture());
        assertThat(plainBody.getValue()).doesNotContainKey("retry_standard_department_id");
    }

    private static final class Fixture {
        private final AgentClient agentClient = mock(AgentClient.class);
        private final ChatRoundPersistence persistence = mock(ChatRoundPersistence.class);
        private final HealthProfileService healthProfiles = mock(HealthProfileService.class);
        private final AgentCallLogService agentCallLogs = mock(AgentCallLogService.class);
        private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        private final ObjectMapper mapper = new ObjectMapper();
        private final Sinks.Many<ServerSentEvent<String>> upstream =
                Sinks.many().replay().all();
        private final ChatRoundService service;

        private Fixture() {
            when(agentClient.chat(any())).thenReturn(upstream.asFlux());
            when(redis.opsForValue()).thenReturn(valueOps);
            // 默认 Redis 全局键不存在：保持"请求空且 Redis 空 -> 省略字段"的现状行为
            when(valueOps.get(anyString())).thenReturn(null);
            when(persistence.recentContext(7L)).thenReturn(List.of(Map.of("role", "user", "content", "你好")));
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
                    redis);
        }

        private ChatRound round(String status) {
            ChatRound round = new ChatRound(12L, "req", 7L, 9L, status);
            round.setId(34L);
            round.setRequestId("req-1");
            return round;
        }

        private ChatRoundService.Command command(String requestId) {
            return new ChatRoundService.Command(12L, requestId, null, "你好", "quick", "triage", null, null, null, null);
        }
    }
}
