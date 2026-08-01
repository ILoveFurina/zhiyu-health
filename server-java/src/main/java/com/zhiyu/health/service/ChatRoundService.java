package com.zhiyu.health.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.ChatRound;
import com.zhiyu.health.entity.Message;
import com.zhiyu.health.rule.RedFlagHit;
import com.zhiyu.health.rule.RedFlagRuleEngine;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** 对话轮次主干：幂等接受、规则前置、独立运行、持久化与实时观察。 */
@Service
@RequiredArgsConstructor
public class ChatRoundService {

    private static final Logger log = LoggerFactory.getLogger(ChatRoundService.class);
    private static final String RED_FLAG_TEMPLATE = "检测到紧急危险信号：%s。%s。导诊已中断。";
    private static final String ERROR_AGENT_FAILED = "AGENT_FAILED";
    private static final String ERROR_PROCESS_RESTARTED = "PROCESS_RESTARTED";

    private final AgentClient agentClient;
    private final ChatRoundPersistence persistence;
    private final RedFlagRuleEngine redFlagRules;
    private final ObjectMapper objectMapper;
    private final Contracts contracts;
    private final HealthProfileService healthProfiles;
    private final Map<Long, RunningRound> running = new ConcurrentHashMap<>();

    /** 同一进程内串行化首次接受，配合数据库唯一约束封住重复消息与重复 Agent 调用。 */
    public synchronized Handle accept(Command command) {
        validate(command);
        ChatRound existing = persistence.find(command.patientId(), command.requestId());
        if (existing != null) {
            return observeExisting(existing);
        }

        // 红线规则先于轮次接受和 Agent 调用执行；规则结果只在本轮新建时计算一次。
        RedFlagHit redFlag = redFlagRules.judge(command.content());
        ChatRound round = persistence.create(
                command.patientId(), command.requestId(), command.conversationId(), command.content());
        RunningRound runtime = new RunningRound(round);
        running.put(round.getId(), runtime);
        if (redFlag != null) {
            runRedFlag(runtime, redFlag);
        } else {
            runAgent(runtime, command);
        }
        return runtime.handle();
    }

    private Handle observeExisting(ChatRound round) {
        RunningRound runtime = running.get(round.getId());
        if (runtime != null) {
            return runtime.handle();
        }
        if (contracts.chatRealtime().completedStatus().equals(round.getStatus())) {
            ObjectNode meta = baseData(round);
            Message message = persistence.finalMessage(round);
            if (message != null && Message.KIND_TEXT.equals(message.getKind())) {
                ObjectNode finalData = baseData(round)
                        .put("message_id", message.getId())
                        .put("role", "assistant")
                        .put("content", message.getContent())
                        .put("effort", message.getEffort())
                        .put("disclaimer", contracts.disclaimer().text());
                return new Handle(
                        round.getRequestId(),
                        round.getConversationId(),
                        round.getStatus(),
                        Flux.just(
                                new Event(contracts.sseEvents().metaEvent(), meta),
                                new Event(contracts.sseEvents().messageEvent(), finalData),
                                new Event(contracts.sseEvents().doneEvent(), baseData(round))));
            }
            if (message != null && "red_flag".equals(message.getKind())) {
                ObjectNode redFlag =
                        baseData(round).put("message_id", message.getId()).put("content", message.getContent());
                return new Handle(
                        round.getRequestId(),
                        round.getConversationId(),
                        round.getStatus(),
                        Flux.just(
                                new Event(contracts.sseEvents().metaEvent(), meta),
                                new Event(contracts.sseEvents().redFlagEvent(), redFlag),
                                new Event(contracts.sseEvents().doneEvent(), baseData(round))));
            }
            return new Handle(
                    round.getRequestId(),
                    round.getConversationId(),
                    round.getStatus(),
                    Flux.just(
                            new Event(contracts.sseEvents().metaEvent(), meta),
                            new Event(contracts.sseEvents().doneEvent(), baseData(round))));
        }
        if (contracts.chatRealtime().acceptedStatus().equals(round.getStatus())
                || contracts.chatRealtime().runningStatus().equals(round.getStatus())) {
            // 进程内没有对应运行态，说明服务曾重启；为避免业务副作用，收敛失败但绝不重放。
            persistence.markFailed(round.getId(), ERROR_PROCESS_RESTARTED);
        }
        return new Handle(
                round.getRequestId(),
                round.getConversationId(),
                contracts.chatRealtime().failedStatus(),
                Flux.error(new RoundFailedException("对话轮次未完成，请从对话记录恢复")));
    }

    private void runRedFlag(RunningRound runtime, RedFlagHit hit) {
        ChatRound round = runtime.round;
        String warning = RED_FLAG_TEMPLATE.formatted(hit.ruleName(), hit.advice());
        Message saved = persistence.completeRedFlag(round, warning);
        runtime.emit(contracts.sseEvents().metaEvent(), baseData(round));
        ObjectNode data = baseData(round)
                .put("message_id", saved.getId())
                .put("rule", hit.ruleName())
                .put("content", warning)
                .put("advice", hit.advice());
        runtime.emit(contracts.sseEvents().redFlagEvent(), data);
        runtime.complete();
    }

    private void runAgent(RunningRound runtime, Command command) {
        ChatRound round = runtime.round;
        persistence.markRunning(round.getId());
        Map<String, Object> body = agentBody(round, command);
        log.info("chat round accepted roundId={} requestId={}", round.getId(), round.getRequestId());
        agentClient.chat(body).subscribe(event -> forward(runtime, event), error -> fail(runtime, error), () -> {
            if (!runtime.sawDone.get()) {
                fail(runtime, new IllegalStateException("Agent 流未发送 done 即结束"));
            }
        });
    }

    private Map<String, Object> agentBody(ChatRound round, Command command) {
        Map<String, Object> body = new HashMap<>();
        body.put("messages", persistence.recentContext(round.getConversationId()));
        body.put("patient_id", round.getPatientId());
        body.put("conversation_id", round.getConversationId());
        body.put(
                "effort",
                blankToDefault(command.effort(), contracts.chatDefaults().effortDefault()));
        body.put(
                "scenario",
                blankToDefault(command.scenario(), contracts.chatDefaults().scenarioDefault()));
        if (command.knowledgeSource() != null && !command.knowledgeSource().isBlank()) {
            body.put("knowledge_source", command.knowledgeSource());
        }
        HealthProfileService.AgentProfileContext profile = healthProfiles.agentContext(round.getPatientId());
        if (profile != null) {
            body.put("health_profile", profile);
        }
        if (command.longitude() != null && command.latitude() != null) {
            body.put("longitude", command.longitude());
            body.put("latitude", command.latitude());
        }
        return body;
    }

    private void forward(RunningRound runtime, ServerSentEvent<String> incoming) {
        if (runtime.terminal.get()) {
            return;
        }
        try {
            runtime.recordUpstream(incoming.event());
            JsonNode raw = parseData(incoming.data());
            JsonNode data = persistence.persistEvent(runtime.round, incoming.event(), raw);
            if (contracts.sseEvents().doneEvent().equals(incoming.event())) {
                runtime.sawDone.set(true);
                persistence.markCompleted(runtime.round.getId());
                runtime.emit(incoming.event(), data);
                runtime.finish();
            } else {
                runtime.emit(incoming.event(), data);
            }
        } catch (RuntimeException | JsonProcessingException error) {
            fail(runtime, error);
        }
    }

    private void fail(RunningRound runtime, Throwable error) {
        if (!runtime.terminal.compareAndSet(false, true)) {
            return;
        }
        persistence.markFailed(runtime.round.getId(), ERROR_AGENT_FAILED);
        running.remove(runtime.round.getId(), runtime);
        log.warn(
                "chat round failed roundId={} events={} costMs={} error={}",
                runtime.round.getId(),
                runtime.events.get(),
                runtime.elapsedMs(),
                error.getClass().getSimpleName());
        runtime.sink.tryEmitError(new RoundFailedException("对话生成失败", error));
    }

    private void validate(Command command) {
        if (command.requestId() == null
                || command.requestId().isBlank()
                || command.requestId().length() > 64) {
            throw new ApiException(400, "request_id 必须为 1 到 64 个字符");
        }
        if (command.content() == null || command.content().isBlank()) {
            throw new ApiException(400, "content 不能为空");
        }
    }

    private JsonNode parseData(String data) throws JsonProcessingException {
        return data == null || data.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(data);
    }

    private ObjectNode baseData(ChatRound round) {
        return objectMapper
                .createObjectNode()
                .put("request_id", round.getRequestId())
                .put("conversation_id", round.getConversationId());
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record Command(
            Long patientId,
            String requestId,
            Long conversationId,
            String content,
            String effort,
            String scenario,
            String knowledgeSource,
            Double longitude,
            Double latitude) {}

    public record Event(String event, JsonNode data) {}

    public record Handle(String requestId, Long conversationId, String status, Flux<Event> events) {}

    public static class RoundFailedException extends RuntimeException {
        public RoundFailedException(String message) {
            super(message);
        }

        public RoundFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final class RunningRound {
        private final ChatRound round;
        private final Sinks.Many<Event> sink = Sinks.many().replay().all();
        private final long acceptedNanos = System.nanoTime();
        private final AtomicLong firstEventNanos = new AtomicLong();
        private final AtomicLong firstTokenNanos = new AtomicLong();
        private final AtomicInteger events = new AtomicInteger();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean sawDone = new AtomicBoolean();

        private RunningRound(ChatRound round) {
            this.round = round;
        }

        private Handle handle() {
            return new Handle(
                    round.getRequestId(),
                    round.getConversationId(),
                    contracts.chatRealtime().acceptedStatus(),
                    sink.asFlux());
        }

        private void recordUpstream(String eventName) {
            long now = System.nanoTime();
            if (firstEventNanos.compareAndSet(0, now)) {
                log.info("chat round first-event roundId={} elapsedMs={}", round.getId(), elapsedMs(now));
            }
            if (contracts.sseEvents().tokenEvent().equals(eventName) && firstTokenNanos.compareAndSet(0, now)) {
                log.info("chat round first-token roundId={} elapsedMs={}", round.getId(), elapsedMs(now));
            }
        }

        private void emit(String eventName, JsonNode data) {
            events.incrementAndGet();
            sink.tryEmitNext(new Event(eventName, data));
        }

        private void complete() {
            emit(contracts.sseEvents().doneEvent(), baseData(round));
            finish();
        }

        private void finish() {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            running.remove(round.getId(), this);
            sink.tryEmitComplete();
            log.info("chat round complete roundId={} events={} costMs={}", round.getId(), events.get(), elapsedMs());
        }

        private long elapsedMs() {
            return elapsedMs(System.nanoTime());
        }

        private long elapsedMs(long now) {
            return (now - acceptedNanos) / 1_000_000;
        }
    }
}
