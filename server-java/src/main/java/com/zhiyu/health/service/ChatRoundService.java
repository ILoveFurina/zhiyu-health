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
import com.zhiyu.health.entity.PreconsultationDraft;
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
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private final AgentCallLogService agentCallLogs;
    // 知识源现场切换补位（ADR-0021）：请求未带 knowledge_source 时读 Redis 全局键
    private final StringRedisTemplate redis;
    // 票 54：预问诊草稿绑定（归属/状态校验、会话回填、摘要快照），普通对话轮次不触达
    private final PreconsultationService preconsultationService;
    private final Map<Long, RunningRound> running = new ConcurrentHashMap<>();

    /** 同一进程内串行化首次接受，配合数据库唯一约束封住重复消息与重复 Agent 调用。 */
    public synchronized Handle accept(Command command) {
        validate(command);
        ChatRound existing = persistence.find(command.patientId(), command.requestId());
        if (existing != null) {
            return observeExisting(existing);
        }
        // 可信预问诊模式（票 54）：仅新建轮次校验草稿归属/状态并强制 preconsultation 场景，
        // 幂等回放（上方 find 命中）不重复校验；客户端裸指定场景不得获得预问诊权限。
        PreconsultationDraft preconsultDraft = resolvePreconsultationDraft(command);

        // 红线规则先于轮次接受和 Agent 调用执行；规则结果只在本轮新建时计算一次。
        RedFlagHit redFlag = redFlagRules.judge(command.content());
        ChatRound round = persistence.create(
                command.patientId(),
                command.requestId(),
                preconsultDraft != null ? preconsultDraft.getConversationId() : command.conversationId(),
                command.content());
        RunningRound runtime = new RunningRound(round);
        running.put(round.getId(), runtime);
        if (preconsultDraft != null && preconsultDraft.getConversationId() == null) {
            // 预问诊首轮惰性建会话：回填草稿关联；此后删除会话只置空关联，草稿保留。
            preconsultationService.attachConversation(preconsultDraft.getId(), round.getConversationId());
        }
        if (redFlag != null) {
            runRedFlag(runtime, redFlag);
        } else {
            runAgent(runtime, command, preconsultDraft);
        }
        return runtime.handle();
    }

    /** 预问诊草稿解析：无草稿标识却裸指定 preconsultation 场景一律 409（场景权限只经草稿获得）。 */
    private PreconsultationDraft resolvePreconsultationDraft(Command command) {
        if (command.preconsultationDraftId() == null) {
            if (contracts.onlineConsultation().scenario().equals(command.scenario())) {
                throw new ApiException(
                        409, contracts.onlineConsultation().texts().get("scenario_requires_draft"));
            }
            return null;
        }
        return preconsultationService.requireForChat(command.patientId(), command.preconsultationDraftId());
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

    /**
     * 通用药品说明书流轮次（票 51，ADR-0028）：chat 信封 medication_name 入口。
     *
     * <p>与对话轮次的差异：不经过红线规则与健康档案注入（C 端不做个性化禁忌判定，
     * 硬约束 2），server-py 只产 token/done 两事件，message 由本端在流尾组装落 KIND_TEXT。
     * 幂等与观察语义复用对话轮次（票 33/40 relay 机制）。
     */
    public synchronized Handle acceptMedication(MedicationCommand command) {
        validateMedication(command);
        ChatRound existing = persistence.find(command.patientId(), command.requestId());
        if (existing != null) {
            return observeExisting(existing);
        }
        ChatRound round = persistence.create(
                command.patientId(), command.requestId(), command.conversationId(), command.drugName());
        RunningRound runtime = new RunningRound(round);
        running.put(round.getId(), runtime);
        runMedication(runtime, command);
        return runtime.handle();
    }

    private void runMedication(RunningRound runtime, MedicationCommand command) {
        ChatRound round = runtime.round;
        persistence.markRunning(round.getId());
        // 入口审计（硬约束 5）：只记脱敏药名（首字 + 长度），不记药名原文
        log.info(
                "medication round accepted roundId={} requestId={} drug={}",
                round.getId(),
                round.getRequestId(),
                maskForAudit(command.drugName()));
        runtime.emit(contracts.sseEvents().metaEvent(), baseData(round));
        agentClient
                .medicationKnowledge(command.drugName())
                .subscribe(event -> forwardMedication(runtime, event), error -> fail(runtime, error), () -> {
                    if (!runtime.sawDone.get()) {
                        fail(runtime, new IllegalStateException("Agent 流未发送 done 即结束"));
                    }
                });
    }

    /** token 透传 + 流尾组装 message；medication 流契约只有 token/done，其余事件忽略。 */
    private void forwardMedication(RunningRound runtime, ServerSentEvent<String> incoming) {
        if (runtime.terminal.get()) {
            return;
        }
        try {
            runtime.recordUpstream(incoming.event());
            JsonNode raw = parseData(incoming.data());
            Contracts.MedicationKnowledge contract = contracts.medicationKnowledge();
            if (contract.tokenEvent().equals(incoming.event())) {
                String text = raw.path("text").asText("");
                runtime.medicationText.append(text);
                emitMedicationToken(runtime, text);
            } else if (contract.doneEvent().equals(incoming.event())) {
                finishMedication(runtime);
            }
        } catch (RuntimeException | JsonProcessingException error) {
            fail(runtime, error);
        }
    }

    /**
     * 流尾出口兜底（硬约束 1）：免责声明缺失时补齐，consult_professional 一律追加；
     * 双话术先以 token 下发（直播气泡可见），随后整段落 KIND_TEXT 并 emit message/done。
     */
    private void finishMedication(RunningRound runtime) {
        ChatRound round = runtime.round;
        Contracts.MedicationKnowledge contract = contracts.medicationKnowledge();
        StringBuilder content = new StringBuilder(runtime.medicationText.toString());
        String disclaimer = contracts.disclaimer().text();
        if (!content.toString().contains(disclaimer)) {
            String tail = "\n\n" + disclaimer;
            content.append(tail);
            emitMedicationToken(runtime, tail);
        }
        String consult = "\n" + contract.consultProfessional();
        content.append(consult);
        emitMedicationToken(runtime, consult);

        ObjectNode messageData =
                objectMapper.createObjectNode().put("role", "assistant").put("content", content.toString());
        JsonNode persisted =
                persistence.persistEvent(round, contracts.sseEvents().messageEvent(), messageData);
        runtime.emit(contracts.sseEvents().messageEvent(), persisted);
        runtime.sawDone.set(true);
        persistence.markCompleted(round.getId());
        JsonNode doneData =
                persistence.persistEvent(round, contracts.sseEvents().doneEvent(), objectMapper.createObjectNode());
        runtime.emit(contracts.sseEvents().doneEvent(), doneData);
        runtime.finish();
    }

    private void emitMedicationToken(RunningRound runtime, String text) {
        runtime.emit(
                contracts.medicationKnowledge().tokenEvent(),
                objectMapper
                        .createObjectNode()
                        .put("request_id", runtime.round.getRequestId())
                        .put("text", text));
    }

    /** 审计脱敏：只保留首字与长度（硬约束 5，药名属患者健康相关原文）。 */
    private String maskForAudit(String drugName) {
        String trimmed = drugName.trim();
        return trimmed.charAt(0) + "***（len=" + trimmed.length() + "）";
    }

    private void validateMedication(MedicationCommand command) {
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

    private void runAgent(RunningRound runtime, Command command, PreconsultationDraft preconsultDraft) {
        ChatRound round = runtime.round;
        runtime.preconsultDraftId = preconsultDraft == null ? null : preconsultDraft.getId();
        persistence.markRunning(round.getId());
        Map<String, Object> body = agentBody(round, command, preconsultDraft);
        log.info("chat round accepted roundId={} requestId={}", round.getId(), round.getRequestId());
        agentClient.chat(body).subscribe(event -> forward(runtime, event), error -> fail(runtime, error), () -> {
            if (!runtime.sawDone.get()) {
                fail(runtime, new IllegalStateException("Agent 流未发送 done 即结束"));
            }
        });
    }

    private Map<String, Object> agentBody(ChatRound round, Command command, PreconsultationDraft preconsultDraft) {
        Map<String, Object> body = new HashMap<>();
        body.put("messages", persistence.recentContext(round.getConversationId()));
        body.put("patient_id", round.getPatientId());
        body.put("conversation_id", round.getConversationId());
        body.put(
                "effort",
                blankToDefault(command.effort(), contracts.chatDefaults().effortDefault()));
        // 票 54：预问诊轮次场景由服务端按草稿强制，不信任请求体 scenario 获得预问诊权限。
        body.put(
                "scenario",
                preconsultDraft != null
                        ? contracts.onlineConsultation().scenario()
                        : blankToDefault(
                                command.scenario(), contracts.chatDefaults().scenarioDefault()));
        // 知识源现场切换（ADR-0021）：优先级"请求 > 全局键 > scenario 默认"。
        // 请求未显式带值时读 Redis 全局键 demo:knowledge_source 补位；两者皆空则省略字段，
        // 交 server-py 按 scenario 默认处理。server-py 完全不感知开关存在。
        String knowledgeSource = resolveKnowledgeSource(command.knowledgeSource());
        if (knowledgeSource != null) {
            body.put("knowledge_source", knowledgeSource);
        }
        // 票 54：预问诊轮次注入草稿锁定的档案（进入时锁定，后续切换激活档案不改变归属）。
        HealthProfileService.AgentProfileContext profile = preconsultDraft != null
                ? healthProfiles.agentContext(round.getPatientId(), preconsultDraft.getHealthProfileId())
                : healthProfiles.agentContext(round.getPatientId());
        if (profile != null) {
            body.put("health_profile", profile);
        }
        if (command.longitude() != null && command.latitude() != null) {
            body.put("longitude", command.longitude());
            body.put("latitude", command.latitude());
        }
        // 票 50：科室号源重试透传（字段名取自契约 retry_request_field，不写死字面量）
        if (command.retryStandardDepartmentId() != null) {
            body.put(contracts.guidedRegistration().retryRequestField(), command.retryStandardDepartmentId());
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
            // 工具进度事件（票 24）：trace 落库走独立可失败路径，不复用 persistEvent 同步事务。
            // 写入失败只 log.warn 不连坐主对话流（ADR-0017：可用性优先于一致性）。
            if (contracts.sseEvents().isTraceEvent(incoming.event())) {
                persistTraceSafely(runtime.round, incoming.event(), raw);
                runtime.emit(incoming.event(), raw);
                return;
            }
            JsonNode data = persistence.persistEvent(runtime.round, incoming.event(), raw);
            if (contracts.sseEvents().doneEvent().equals(incoming.event())) {
                runtime.sawDone.set(true);
                persistence.markCompleted(runtime.round.getId());
                runtime.emit(incoming.event(), data);
                runtime.finish();
            } else {
                // 票 54：预问诊成功轮次的摘要快照随 message 事件落草稿（旁路，不连坐对话流）。
                if (runtime.preconsultDraftId != null
                        && contracts.sseEvents().messageEvent().equals(incoming.event())) {
                    applySummarySafely(runtime.preconsultDraftId, data);
                }
                runtime.emit(incoming.event(), data);
            }
        } catch (RuntimeException | JsonProcessingException error) {
            fail(runtime, error);
        }
    }

    /** 票 54：摘要快照更新失败只记日志（不记内容，防泄漏病情原文），保留上一版且不打断对话流。 */
    private void applySummarySafely(long draftId, JsonNode messageData) {
        JsonNode summary = messageData.get(contracts.onlineConsultation().summaryEventField());
        if (summary == null || !summary.isObject()) {
            return;
        }
        try {
            preconsultationService.applySummary(draftId, summary);
        } catch (RuntimeException error) {
            log.warn(
                    "preconsultation summary apply failed draftId={} error={}",
                    draftId,
                    error.getClass().getSimpleName());
        }
    }

    /**
     * trace 落库独立可失败路径（ADR-0017）：异常只 log.warn（不记异常 message 以免泄漏 SQL/连接串），
     * 不向 C 端下发错误、不写 chat_rounds.error_code（trace 落库失败不是轮次失败）。
     * 只记 roundId/toolCallId/toolName/phase/异常类名，不记异常 message。
     */
    private void persistTraceSafely(ChatRound round, String eventName, JsonNode data) {
        try {
            agentCallLogs.append(
                    new AgentCallLogService.ChatRoundState(
                            round.getId(), round.getConversationId(), round.getPatientId()),
                    eventName,
                    data);
        } catch (RuntimeException error) {
            // 从 data 取 toolCallId/toolName 贴齐 ADR-0017 的日志字段集（不含异常 message）
            String toolCallId = data != null && data.hasNonNull("tool_call_id")
                    ? data.path("tool_call_id").asText()
                    : null;
            String toolName = data != null && data.hasNonNull("tool_name")
                    ? data.path("tool_name").asText()
                    : null;
            log.warn(
                    "agent_call_logs append failed roundId={} toolCallId={} toolName={} phase={} error={}",
                    round.getId(),
                    toolCallId,
                    toolName,
                    eventName,
                    error.getClass().getSimpleName());
        }
    }

    private void fail(RunningRound runtime, Throwable error) {
        if (!runtime.terminal.compareAndSet(false, true)) {
            return;
        }
        persistence.markFailed(runtime.round.getId(), ERROR_AGENT_FAILED);
        running.remove(runtime.round.getId(), runtime);
        // 清理 trace 配对状态，避免 roundStates 内存泄漏（票 24）
        agentCallLogs.clearRound(runtime.round.getId());
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

    /**
     * 知识源三级解析（ADR-0021）：请求带值用请求；请求空读 Redis 全局键；Redis 也空返回 null
     * （调用方据此省略 body 字段，交 server-py 走 scenario 默认）。
     */
    private String resolveKnowledgeSource(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        String global = redis.opsForValue().get(contracts.demoArsenal().knowledgeSourceRedisKey());
        return (global == null || global.isBlank()) ? null : global;
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
            Double latitude,
            Long retryStandardDepartmentId,
            // 票 54：预问诊草稿标识；非空时服务端校验归属/状态并强制 preconsultation 场景
            Long preconsultationDraftId) {}

    /** 药品说明书流轮次命令（票 51）：无 effort/scenario/档案/定位，只有药名。 */
    public record MedicationCommand(Long patientId, String requestId, Long conversationId, String drugName) {}

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
        // 药品说明书流（票 51）：server-py 只产 token/done，本端逐 token 累积，流尾组装 message
        private final StringBuilder medicationText = new StringBuilder();
        // 票 54：预问诊轮次绑定的草稿 id（仅 Agent 轮次非空），message 事件触发摘要快照旁路更新
        private Long preconsultDraftId;

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
            // 清理 trace 配对状态，避免 roundStates 内存泄漏（票 24）
            agentCallLogs.clearRound(round.getId());
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
