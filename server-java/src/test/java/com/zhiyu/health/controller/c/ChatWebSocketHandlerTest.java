package com.zhiyu.health.controller.c;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.ChatWebSocketHandshakeInterceptor;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.service.ChatRoundService;
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
