package com.zhiyu.health.controller.patient.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.config.ChatWebSocketHandshakeInterceptor;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.service.chat.ChatRoundModels;
import com.zhiyu.health.service.chat.ChatRoundService;
import com.zhiyu.health.service.common.PatientTokenService;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.Disposable;

/** C 端页面级 WebSocket 传输适配器；只收发契约信封，不拥有对话轮次。 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_BYTES = 256 * 1024;

    private final ChatRoundService rounds;
    private final ObjectMapper objectMapper;
    private final Contracts contracts;
    private final PatientTokenService patientTokens;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(
            ChatRoundService rounds,
            ObjectMapper objectMapper,
            Contracts contracts,
            PatientTokenService patientTokens) {
        this.rounds = rounds;
        this.objectMapper = objectMapper;
        this.contracts = contracts;
        this.patientTokens = patientTokens;
    }

    /** 存量 handler 单测使用握手属性模拟已认证连接；生产装配始终注入 PatientTokenService。 */
    ChatWebSocketHandler(ChatRoundService rounds, ObjectMapper objectMapper, Contracts contracts) {
        this(rounds, objectMapper, contracts, null);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 轮次事件由反应堆线程推送、容器写锁不归我们持有：装饰器把并发写串行化并限时，
        // 防止慢客户端阻塞上游；只作写通道，不缓冲业务数据。
        Object trustedPatientId = session.getAttributes().get(ChatWebSocketHandshakeInterceptor.ATTR_PATIENT_ID);
        Long patientId = trustedPatientId instanceof Number number ? number.longValue() : null;
        sessions.put(
                session.getId(),
                new SessionState(
                        new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_BYTES),
                        patientId));
    }

    @Override
    protected void handleTextMessage(WebSocketSession rawSession, TextMessage message) {
        SessionState state = sessions.get(rawSession.getId());
        if (state == null) {
            return;
        }
        IncomingEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getPayload(), IncomingEnvelope.class);
        } catch (Exception error) {
            // WebSocket 消息循环不经 @ControllerAdvice，解析失败只能在站内转为契约 error 信封
            sendError(state.session, null, "INVALID_ENVELOPE", "消息信封格式无效");
            return;
        }
        if (state.patientId == null) {
            authenticate(rawSession, state, envelope);
            return;
        }
        if (contracts.chatRealtime().authEnvelope().equals(envelope.type())) {
            sendError(state.session, null, "ALREADY_AUTHENTICATED", "WebSocket 会话已完成认证");
            return;
        }
        if (!contracts.chatRealtime().chatEnvelope().equals(envelope.type())
                || envelope.requestId() == null
                || envelope.data() == null) {
            sendError(state.session, envelope.requestId(), "INVALID_ENVELOPE", "仅支持 chat 信封且必须携带 request_id");
            return;
        }
        // 同一连接同一时刻仅一轮：原子占用防止并发 chat 信封挤入并打断进行中的轮次
        if (!state.busy.compareAndSet(false, true)) {
            sendError(state.session, envelope.requestId(), "ROUND_IN_PROGRESS", "当前对话轮次尚未完成");
            return;
        }

        try {
            ChatPayload data = objectMapper.treeToValue(envelope.data(), ChatPayload.class);
            boolean hasMedication =
                    data.medicationName() != null && !data.medicationName().isBlank();
            boolean hasContent = data.content() != null && !data.content().isBlank();
            if (hasMedication == hasContent) {
                // 契约：medication_name 与 text 互斥且必居其一（票 51，与 HTTP 通道同一 XOR 规则）
                throw new IllegalArgumentException("content 与 medication_name 必须且只能携带其一");
            }
            ChatRoundModels.Handle handle = hasMedication
                    ? rounds.acceptMedication(new ChatRoundModels.MedicationCommand(
                            state.patientId, envelope.requestId(), data.conversationId(), data.medicationName()))
                    : rounds.accept(new ChatRoundModels.Command(
                            state.patientId,
                            envelope.requestId(),
                            data.conversationId(),
                            data.content(),
                            data.effort(),
                            data.scenario(),
                            data.knowledgeSource(),
                            data.longitude(),
                            data.latitude(),
                            data.retryStandardDepartmentId(),
                            data.preconsultationDraftId(),
                            data.prescriptionId()));
            sendAccepted(state.session, handle);
            state.observer = handle.events()
                    .subscribe(
                            event -> sendEvent(state.session, handle.requestId(), event),
                            error -> {
                                sendError(state.session, handle.requestId(), "ROUND_FAILED", "对话处理失败，请稍后重试");
                                state.busy.set(false);
                            },
                            () -> state.busy.set(false));
        } catch (Exception error) {
            // 与解析分支同理：accept 的校验/装配异常不经 @ControllerAdvice，就地转为 error 信封并释放占用
            state.busy.set(false);
            sendError(state.session, envelope.requestId(), "CHAT_REJECTED", "消息未能受理，请检查后重试");
        }
    }

    private void authenticate(WebSocketSession rawSession, SessionState state, IncomingEnvelope envelope) {
        if (!contracts.chatRealtime().authEnvelope().equals(envelope.type()) || envelope.data() == null) {
            sendError(state.session, null, "AUTH_REQUIRED", "请先完成 WebSocket 会话认证");
            closePolicyViolation(rawSession);
            return;
        }
        try {
            AuthPayload data = objectMapper.treeToValue(envelope.data(), AuthPayload.class);
            if (patientTokens == null) {
                throw new IllegalStateException("患者令牌校验器不可用");
            }
            state.patientId = patientTokens.verify(data.token());
            send(
                    state.session,
                    new OutgoingEnvelope(
                            contracts.chatRealtime().authenticatedEnvelope(),
                            null,
                            null,
                            objectMapper.createObjectNode().put("status", "ok")));
        } catch (Exception error) {
            sendError(state.session, null, "AUTH_INVALID", "WebSocket 认证失败");
            closePolicyViolation(rawSession);
        }
    }

    private void closePolicyViolation(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException ignored) {
            // 认证失败后的关闭仅负责回收观察通道，无业务状态需要补偿。
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionState state = sessions.remove(session.getId());
        if (state != null && state.observer != null) {
            // 只移除实时订阅者；ChatRoundService 的上游订阅不受影响。
            state.observer.dispose();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private void sendAccepted(WebSocketSession session, ChatRoundModels.Handle handle) {
        ObjectNode data = objectMapper
                .createObjectNode()
                .put("conversation_id", handle.conversationId())
                .put("status", handle.status());
        send(
                session,
                new OutgoingEnvelope(contracts.chatRealtime().acceptedEnvelope(), handle.requestId(), null, data));
    }

    private void sendEvent(WebSocketSession session, String requestId, ChatRoundModels.Event event) {
        send(
                session,
                new OutgoingEnvelope(contracts.chatRealtime().eventEnvelope(), requestId, event.event(), event.data()));
    }

    private void sendError(WebSocketSession session, String requestId, String code, String message) {
        ObjectNode data =
                objectMapper.createObjectNode().put("code", code).put("message", message == null ? "对话处理失败" : message);
        send(session, new OutgoingEnvelope(contracts.chatRealtime().errorEnvelope(), requestId, null, data));
    }

    private void send(WebSocketSession session, OutgoingEnvelope envelope) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
        } catch (IOException ignored) {
            // 观察通道写失败不影响已接受轮次；关闭回调负责移除订阅者。
        }
    }

    private record IncomingEnvelope(String type, @JsonProperty("request_id") String requestId, JsonNode data) {}

    private record AuthPayload(String token) {}

    private record OutgoingEnvelope(
            String type, @JsonProperty("request_id") String requestId, String event, JsonNode data) {}

    private record ChatPayload(
            String content,
            @JsonProperty("medication_name") String medicationName,
            @JsonProperty("conversation_id") Long conversationId,
            String effort,
            String scenario,
            @JsonProperty("knowledge_source") String knowledgeSource,
            Double longitude,
            Double latitude,
            // 票 50：科室号源查询失败后的重试字段（契约 chat_optional_fields）
            @JsonProperty("retry_standard_department_id") Long retryStandardDepartmentId,
            // 票 55：预问诊草稿标识（与 HTTP 通道同一字段名）
            @JsonProperty("preconsultation_draft_id") Long preconsultationDraftId,
            // 票 80：处方选择卡点选回传的所选处方 ID（与 HTTP 通道同一字段名）
            @JsonProperty("prescription_id") Long prescriptionId) {}

    private static final class SessionState {
        private final WebSocketSession session;
        private final AtomicBoolean busy = new AtomicBoolean();
        private volatile Long patientId;
        private Disposable observer;

        private SessionState(WebSocketSession session, Long patientId) {
            this.session = session;
            this.patientId = patientId;
        }
    }
}
