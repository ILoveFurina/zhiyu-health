package com.zhiyu.health.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.chat.ChatRound;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;

/**
 * 通用药品知识流：只中继 token/done，不注入健康档案、不运行个性化禁忌规则。
 * 这是医疗安全边界，C 端药品问答只能提供通用知识并引导咨询医生或药师。
 */
final class MedicationKnowledgeRelay {
    private static final Logger log = LoggerFactory.getLogger(MedicationKnowledgeRelay.class);

    private final AgentClient agentClient;
    private final ChatRoundPersistence persistence;
    private final ObjectMapper objectMapper;
    private final Contracts contracts;

    MedicationKnowledgeRelay(
            AgentClient agentClient, ChatRoundPersistence persistence, ObjectMapper objectMapper, Contracts contracts) {
        this.agentClient = agentClient;
        this.persistence = persistence;
        this.objectMapper = objectMapper;
        this.contracts = contracts;
    }

    void validate(ChatRoundModels.MedicationCommand command) {
        if (command.requestId() == null
                || command.requestId().isBlank()
                || command.requestId().length() > 64) {
            throw new ApiException(400, "request_id 必须为 1 到 64 个字符");
        }
        if (command.drugName() == null
                || command.drugName().isBlank()
                || command.drugName().length() > 100) {
            throw new ApiException(400, "medication_name 必须为 1 到 100 个字符");
        }
    }

    void start(Runtime runtime, ChatRoundModels.MedicationCommand command) {
        ChatRound round = runtime.round();
        StringBuilder content = new StringBuilder();
        AtomicBoolean sawDone = new AtomicBoolean();
        persistence.markRunning(round.getId());
        log.info(
                "medication round accepted roundId={} requestId={} drug={}",
                round.getId(),
                round.getRequestId(),
                maskForAudit(command.drugName()));
        runtime.emit(contracts.sseEvents().metaEvent(), baseData(round));
        agentClient
                .medicationKnowledge(command.drugName())
                .subscribe(event -> forward(runtime, event, content, sawDone), runtime::fail, () -> {
                    if (!sawDone.get()) {
                        runtime.fail(new IllegalStateException("Agent 流未发送 done 即结束"));
                    }
                });
    }

    private void forward(
            Runtime runtime, ServerSentEvent<String> incoming, StringBuilder content, AtomicBoolean sawDone) {
        if (runtime.terminal()) {
            return;
        }
        try {
            runtime.recordUpstream(incoming.event());
            JsonNode raw = incoming.data() == null || incoming.data().isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(incoming.data());
            Contracts.MedicationKnowledge contract = contracts.medicationKnowledge();
            if (contract.tokenEvent().equals(incoming.event())) {
                String text = raw.path("text").asText("");
                content.append(text);
                emitToken(runtime, text);
            } else if (contract.doneEvent().equals(incoming.event())) {
                finish(runtime, content);
                sawDone.set(true);
            }
        } catch (Exception error) {
            runtime.fail(error);
        }
    }

    /** 流尾统一补免责声明与专业咨询提示，再持久化完整 assistant 消息。 */
    private void finish(Runtime runtime, StringBuilder content) {
        ChatRound round = runtime.round();
        String disclaimer = contracts.disclaimer().text();
        if (!content.toString().contains(disclaimer)) {
            String tail = "\n\n" + disclaimer;
            content.append(tail);
            emitToken(runtime, tail);
        }
        String consult = "\n" + contracts.medicationKnowledge().consultProfessional();
        content.append(consult);
        emitToken(runtime, consult);

        ObjectNode message =
                objectMapper.createObjectNode().put("role", "assistant").put("content", content.toString());
        JsonNode persisted =
                persistence.persistEvent(round, contracts.sseEvents().messageEvent(), message);
        runtime.emit(contracts.sseEvents().messageEvent(), persisted);
        persistence.markCompleted(round.getId());
        JsonNode done =
                persistence.persistEvent(round, contracts.sseEvents().doneEvent(), objectMapper.createObjectNode());
        runtime.emit(contracts.sseEvents().doneEvent(), done);
        runtime.finish();
    }

    private void emitToken(Runtime runtime, String text) {
        runtime.emit(
                contracts.medicationKnowledge().tokenEvent(),
                objectMapper
                        .createObjectNode()
                        .put("request_id", runtime.round().getRequestId())
                        .put("text", text));
    }

    private ObjectNode baseData(ChatRound round) {
        return objectMapper
                .createObjectNode()
                .put("request_id", round.getRequestId())
                .put("conversation_id", round.getConversationId());
    }

    private String maskForAudit(String drugName) {
        String trimmed = drugName.trim();
        return trimmed.charAt(0) + "***（len=" + trimmed.length() + "）";
    }

    interface Runtime {
        ChatRound round();

        boolean terminal();

        void recordUpstream(String eventName);

        void emit(String eventName, JsonNode data);

        void finish();

        void fail(Throwable error);
    }
}
