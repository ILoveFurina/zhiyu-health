package com.zhiyu.health.controller.patient.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.ChatWebSocketHandshakeInterceptor;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.service.chat.ChatRoundService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** WebSocket 公共协议：结构化信封、单轮互斥与首 token 低延迟转发。 */
class ChatWebSocketHandlerTest {

    private static final Contracts CONTRACTS = Contracts.load(Path.of("../contracts"));

    @Test
    void acceptedAndFirstTokenCarrySameRequestIdWithinOneHundredMilliseconds() throws Exception {
        ChatRoundService rounds = mock(ChatRoundService.class);
        Sinks.Many<ChatRoundService.Event> events = Sinks.many().replay().all();
        when(rounds.accept(any())).thenReturn(new ChatRoundService.Handle("req-34", 7L, "ACCEPTED", events.asFlux()));
        ObjectMapper mapper = new ObjectMapper();
        ChatWebSocketHandler handler = new ChatWebSocketHandler(rounds, mapper, CONTRACTS);
        List<String> sent = new ArrayList<>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(
                session,
                new TextMessage(
                        """
                        {"type":"chat","request_id":"req-34","data":{"content":"你好"}}
                        """));
        long started = System.nanoTime();
        events.tryEmitNext(new ChatRoundService.Event("token", mapper.readTree("{\"text\":\"你\"}")));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMs).isLessThanOrEqualTo(100);
        assertThat(sent).hasSize(2);
        JsonNode accepted = mapper.readTree(sent.get(0));
        JsonNode token = mapper.readTree(sent.get(1));
        assertThat(accepted.path("type").asText()).isEqualTo("accepted");
        assertThat(token.path("type").asText()).isEqualTo("event");
        assertThat(token.path("event").asText()).isEqualTo("token");
        assertThat(accepted.path("request_id").asText()).isEqualTo("req-34");
        assertThat(token.path("request_id").asText()).isEqualTo("req-34");
    }

    @Test
    void secondRoundIsRejectedWhileFirstIsRunning() throws Exception {
        ChatRoundService rounds = mock(ChatRoundService.class);
        when(rounds.accept(any())).thenReturn(new ChatRoundService.Handle("req-1", 7L, "ACCEPTED", Flux.never()));
        ObjectMapper mapper = new ObjectMapper();
        ChatWebSocketHandler handler = new ChatWebSocketHandler(rounds, mapper, CONTRACTS);
        List<String> sent = new ArrayList<>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(
                session, new TextMessage("{\"type\":\"chat\",\"request_id\":\"req-1\",\"data\":{\"content\":\"一\"}}"));
        handler.handleTextMessage(
                session, new TextMessage("{\"type\":\"chat\",\"request_id\":\"req-2\",\"data\":{\"content\":\"二\"}}"));

        JsonNode error = mapper.readTree(sent.get(1));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("request_id").asText()).isEqualTo("req-2");
        assertThat(error.path("data").path("code").asText()).isEqualTo("ROUND_IN_PROGRESS");
    }

    @Test
    void medicationNameEnvelopeRoutesToMedicationRound() throws Exception {
        // 票 51：chat 信封携带 medication_name 时走说明书流轮次，token 经 event 信封透传
        ChatRoundService rounds = mock(ChatRoundService.class);
        Sinks.Many<ChatRoundService.Event> events = Sinks.many().replay().all();
        when(rounds.acceptMedication(any()))
                .thenReturn(new ChatRoundService.Handle("req-med", 7L, "ACCEPTED", events.asFlux()));
        ObjectMapper mapper = new ObjectMapper();
        ChatWebSocketHandler handler = new ChatWebSocketHandler(rounds, mapper, CONTRACTS);
        List<String> sent = new ArrayList<>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(
                session,
                new TextMessage(
                        """
                        {"type":"chat","request_id":"req-med","data":{"medication_name":"阿莫西林胶囊"}}
                        """));
        events.tryEmitNext(new ChatRoundService.Event("token", mapper.readTree("{\"text\":\"【用途】\"}")));

        org.mockito.Mockito.verify(rounds)
                .acceptMedication(new ChatRoundService.MedicationCommand(12L, "req-med", null, "阿莫西林胶囊"));
        org.mockito.Mockito.verify(rounds, org.mockito.Mockito.never()).accept(any());
        JsonNode accepted = mapper.readTree(sent.get(0));
        JsonNode token = mapper.readTree(sent.get(1));
        assertThat(accepted.path("type").asText()).isEqualTo("accepted");
        assertThat(token.path("event").asText()).isEqualTo("token");
        assertThat(token.path("request_id").asText()).isEqualTo("req-med");
    }

    @Test
    void contentAndMedicationNameTogetherAreRejected() throws Exception {
        // 契约：medication_name 与 text 互斥
        ChatRoundService rounds = mock(ChatRoundService.class);
        ObjectMapper mapper = new ObjectMapper();
        ChatWebSocketHandler handler = new ChatWebSocketHandler(rounds, mapper, CONTRACTS);
        List<String> sent = new ArrayList<>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(
                session,
                new TextMessage(
                        """
                        {"type":"chat","request_id":"req-x","data":{"content":"你好","medication_name":"布洛芬"}}
                        """));

        JsonNode error = mapper.readTree(sent.get(0));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("data").path("code").asText()).isEqualTo("CHAT_REJECTED");
        org.mockito.Mockito.verify(rounds, org.mockito.Mockito.never()).accept(any());
        org.mockito.Mockito.verify(rounds, org.mockito.Mockito.never()).acceptMedication(any());
    }

    @Test
    void neitherContentNorMedicationNameIsRejected() throws Exception {
        // 契约：medication_name 与 text 必居其一（与 HTTP 通道同一 XOR 规则），
        // 两者皆空的信封不得落入携带 null content 的普通对话轮次
        ChatRoundService rounds = mock(ChatRoundService.class);
        ObjectMapper mapper = new ObjectMapper();
        ChatWebSocketHandler handler = new ChatWebSocketHandler(rounds, mapper, CONTRACTS);
        List<String> sent = new ArrayList<>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(
                session,
                new TextMessage(
                        """
                        {"type":"chat","request_id":"req-x","data":{}}
                        """));

        JsonNode error = mapper.readTree(sent.get(0));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("data").path("code").asText()).isEqualTo("CHAT_REJECTED");
        org.mockito.Mockito.verify(rounds, org.mockito.Mockito.never()).accept(any());
        org.mockito.Mockito.verify(rounds, org.mockito.Mockito.never()).acceptMedication(any());
    }

    @Test
    void retryStandardDepartmentIdEnvelopeIsForwardedToRoundCommand() throws Exception {
        // 票 50：WS chat 信封携带 retry_standard_department_id 时透传给对话轮次命令
        ChatRoundService rounds = mock(ChatRoundService.class);
        Sinks.Many<ChatRoundService.Event> events = Sinks.many().replay().all();
        when(rounds.accept(any()))
                .thenReturn(new ChatRoundService.Handle("req-retry", 7L, "ACCEPTED", events.asFlux()));
        ObjectMapper mapper = new ObjectMapper();
        ChatWebSocketHandler handler = new ChatWebSocketHandler(rounds, mapper, CONTRACTS);
        List<String> sent = new ArrayList<>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(
                session,
                new TextMessage(
                        """
                        {"type":"chat","request_id":"req-retry",
                         "data":{"content":"重新查询号源","retry_standard_department_id":3}}
                        """));

        org.mockito.Mockito.verify(rounds)
                .accept(new ChatRoundService.Command(
                        12L, "req-retry", null, "重新查询号源", null, null, null, null, null, 3L, null));
        JsonNode accepted = mapper.readTree(sent.get(0));
        assertThat(accepted.path("type").asText()).isEqualTo("accepted");
        assertThat(accepted.path("request_id").asText()).isEqualTo("req-retry");
    }

    private WebSocketSession session(List<String> sent) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ChatWebSocketHandshakeInterceptor.ATTR_PATIENT_ID, 12L);
        when(session.getId()).thenReturn("socket-1");
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        when(session.getTextMessageSizeLimit()).thenReturn(64 * 1024);
        when(session.getBinaryMessageSizeLimit()).thenReturn(64 * 1024);
        doAnswer(invocation -> {
                    WebSocketMessage<?> message = invocation.getArgument(0);
                    sent.add(String.valueOf(message.getPayload()));
                    return null;
                })
                .when(session)
                .sendMessage(any());
        return session;
    }
}
