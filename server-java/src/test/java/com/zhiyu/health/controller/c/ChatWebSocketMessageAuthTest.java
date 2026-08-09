package com.zhiyu.health.controller.patient.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.config.JwtKeys;
import com.zhiyu.health.service.chat.ChatRoundModels;
import com.zhiyu.health.service.chat.ChatRoundService;
import com.zhiyu.health.service.common.PatientTokenService;
import io.jsonwebtoken.Jwts;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Flux;

/** 隧道兼容的 WebSocket 消息层鉴权：upgrade 不持 JWT，auth 成功后才允许 chat。 */
class ChatWebSocketMessageAuthTest {

    private static final String SECRET = "test-secret-key-0123456789abcdef0123456789";
    private static final Contracts CONTRACTS = Contracts.load(Path.of("../contracts"));
    private final ObjectMapper mapper = new ObjectMapper();
    private final PatientTokenService tokens = new PatientTokenService(SECRET, 720);

    @Test
    void validAuthEnablesChatWithoutHandshakeIdentity() throws Exception {
        ChatRoundService rounds = mock(ChatRoundService.class);
        when(rounds.accept(any())).thenReturn(new ChatRoundModels.Handle("req-1", 7L, "ACCEPTED", Flux.empty()));
        ChatWebSocketHandler handler = new ChatWebSocketHandler(rounds, mapper, CONTRACTS, tokens);
        List<String> sent = new ArrayList<>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(
                session, new TextMessage("{\"type\":\"auth\",\"data\":{\"token\":\"" + tokens.issue(12L) + "\"}}"));
        handler.handleTextMessage(
                session, new TextMessage("{\"type\":\"chat\",\"request_id\":\"req-1\",\"data\":{\"content\":\"你好\"}}"));

        assertThat(mapper.readTree(sent.get(0)).path("type").asText()).isEqualTo("authenticated");
        assertThat(mapper.readTree(sent.get(1)).path("type").asText()).isEqualTo("accepted");
        verify(rounds)
                .accept(new ChatRoundModels.Command(
                        12L, "req-1", null, "你好", null, null, null, null, null, null, null, null));
    }

    @Test
    void chatBeforeAuthIsRejected() throws Exception {
        ChatRoundService rounds = mock(ChatRoundService.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(rounds, mapper, CONTRACTS, tokens);
        List<String> sent = new ArrayList<>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(
                session, new TextMessage("{\"type\":\"chat\",\"request_id\":\"req-1\",\"data\":{\"content\":\"你好\"}}"));

        JsonNode error = mapper.readTree(sent.get(0));
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("data").path("code").asText()).isEqualTo("AUTH_REQUIRED");
        verify(rounds, never()).accept(any());
    }

    @Test
    void invalidTokenIsRejectedWithoutLeakingRawToken() throws Exception {
        ChatRoundService rounds = mock(ChatRoundService.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(rounds, mapper, CONTRACTS, tokens);
        List<String> sent = new ArrayList<>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(
                session, new TextMessage("{\"type\":\"auth\",\"data\":{\"token\":\"secret-invalid-token\"}}"));

        JsonNode error = mapper.readTree(sent.get(0));
        assertThat(error.path("data").path("code").asText()).isEqualTo("AUTH_INVALID");
        assertThat(sent.get(0)).doesNotContain("secret-invalid-token");
        verify(rounds, never()).accept(any());
    }

    @Test
    void staffTokenCannotAuthenticatePatientSocket() throws Exception {
        ChatRoundService rounds = mock(ChatRoundService.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(rounds, mapper, CONTRACTS, tokens);
        List<String> sent = new ArrayList<>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);
        String staffToken = Jwts.builder()
                .subject("7")
                .claim("scope", "staff")
                .signWith(JwtKeys.hmacShaKey(SECRET))
                .compact();

        handler.handleTextMessage(
                session, new TextMessage("{\"type\":\"auth\",\"data\":{\"token\":\"" + staffToken + "\"}}"));

        JsonNode error = mapper.readTree(sent.get(0));
        assertThat(error.path("data").path("code").asText()).isEqualTo("AUTH_INVALID");
        verify(rounds, never()).accept(any());
    }

    @Test
    void repeatedAuthDoesNotChangePatientIdentity() throws Exception {
        ChatRoundService rounds = mock(ChatRoundService.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(rounds, mapper, CONTRACTS, tokens);
        List<String> sent = new ArrayList<>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(
                session, new TextMessage("{\"type\":\"auth\",\"data\":{\"token\":\"" + tokens.issue(12L) + "\"}}"));
        handler.handleTextMessage(
                session, new TextMessage("{\"type\":\"auth\",\"data\":{\"token\":\"" + tokens.issue(99L) + "\"}}"));

        JsonNode error = mapper.readTree(sent.get(1));
        assertThat(error.path("data").path("code").asText()).isEqualTo("ALREADY_AUTHENTICATED");
    }

    private WebSocketSession session(List<String> sent) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-auth");
        when(session.getAttributes()).thenReturn(new HashMap<>());
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
