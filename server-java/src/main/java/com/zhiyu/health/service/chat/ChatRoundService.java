package com.zhiyu.health.service.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyu.health.agentclient.AgentClient;
import com.zhiyu.health.config.ApiException;
import com.zhiyu.health.config.Contracts;
import com.zhiyu.health.entity.chat.ChatRound;
import com.zhiyu.health.entity.chat.Message;
import com.zhiyu.health.entity.chat.PreconsultationDraft;
import com.zhiyu.health.rule.RedFlagHit;
import com.zhiyu.health.rule.RedFlagRuleEngine;
import com.zhiyu.health.service.chat.ChatRoundModels.Command;
import com.zhiyu.health.service.chat.ChatRoundModels.Event;
import com.zhiyu.health.service.chat.ChatRoundModels.Handle;
import com.zhiyu.health.service.chat.ChatRoundModels.MedicationCommand;
import com.zhiyu.health.service.chat.ChatRoundModels.RoundFailedException;
import com.zhiyu.health.service.health.HealthProfileService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** 对话轮次主干：幂等接受、规则前置、独立运行、持久化与实时观察。 */
@Service
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
    // 票 55：预问诊草稿绑定（归属/状态校验、会话回填、摘要快照），普通对话轮次不触达
    private final PreconsultationService preconsultationService;
    private final Map<Long, RunningRound> running = new ConcurrentHashMap<>();
    private final MedicationKnowledgeRelay medicationRelay;

    public ChatRoundService(
            AgentClient agentClient,
            ChatRoundPersistence persistence,
            RedFlagRuleEngine redFlagRules,
            ObjectMapper objectMapper,
            Contracts contracts,
            HealthProfileService healthProfiles,
            AgentCallLogService agentCallLogs,
            StringRedisTemplate redis,
            PreconsultationService preconsultationService) {
        this.agentClient = agentClient;
        this.persistence = persistence;
        this.redFlagRules = redFlagRules;
        this.objectMapper = objectMapper;
        this.contracts = contracts;
        this.healthProfiles = healthProfiles;
        this.agentCallLogs = agentCallLogs;
        this.redis = redis;
        this.preconsultationService = preconsultationService;
        this.medicationRelay = new MedicationKnowledgeRelay(agentClient, persistence, objectMapper, contracts);
    }

    /** 同一进程内串行化首次接受，配合数据库唯一约束封住重复消息与重复 Agent 调用。 */
    public synchronized Handle accept(Command command) {
        validate(command);
        ChatRound existing = persistence.find(command.patientId(), command.requestId());
        if (existing != null) {
            return observeExisting(existing);
        }
        // 可信预问诊模式（票 55）：仅新建轮次校验草稿归属/状态并强制 preconsultation 场景，
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
        medicationRelay.validate(command);
        ChatRound existing = persistence.find(command.patientId(), command.requestId());
        if (existing != null) {
            return observeExisting(existing);
        }
        ChatRound round = persistence.create(
                command.patientId(), command.requestId(), command.conversationId(), command.drugName());
        RunningRound runtime = new RunningRound(round);
        running.put(round.getId(), runtime);
        medicationRelay.start(runtime, command);
        return runtime.handle();
    }

    private void runAgent(RunningRound runtime, Command command, PreconsultationDraft preconsultDraft) {
        ChatRound round = runtime.round;
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
        // 票 55：预问诊轮次场景由服务端按草稿强制，不信任请求体 scenario 获得预问诊权限。
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
        // 票 55：预问诊轮次注入草稿锁定的档案（进入时锁定，后续切换激活档案不改变归属）。
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
        // 票 55：预问诊草稿标识透传给 server-py，供异步摘要 task 回调本端落草稿
        // （摘要不再随 message 事件下发，改为 done 后后台整理并回调 /api/agent/preconsultation-drafts/{id}/summary）
        if (preconsultDraft != null) {
            body.put("preconsultation_draft_id", preconsultDraft.getId());
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
            // high 档思考增量只做实时中继：内容可能复述患者症状，禁止进入 messages
            // 与 agent_call_logs；直播结束后即丢弃（票 70，硬约束 5）。
            if (contracts.chatRealtime().thinkingEvent().equals(incoming.event())) {
                runtime.emit(incoming.event(), raw);
                return;
            }
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
                // 票 55 改造：摘要不再随 message 事件下发（改为 server-py 后台异步回调
                // /api/agent/preconsultation-drafts/{id}/summary 落草稿），message 分支
                // 不再旁路触发 applySummary，避免重复落库。
                runtime.emit(incoming.event(), data);
            }
        } catch (RuntimeException | JsonProcessingException error) {
            fail(runtime, error);
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

    private final class RunningRound implements MedicationKnowledgeRelay.Runtime {
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

        @Override
        public ChatRound round() {
            return round;
        }

        @Override
        public boolean terminal() {
            return terminal.get();
        }

        @Override
        public void recordUpstream(String eventName) {
            long now = System.nanoTime();
            if (firstEventNanos.compareAndSet(0, now)) {
                log.info("chat round first-event roundId={} elapsedMs={}", round.getId(), elapsedMs(now));
            }
            if (contracts.sseEvents().tokenEvent().equals(eventName) && firstTokenNanos.compareAndSet(0, now)) {
                log.info("chat round first-token roundId={} elapsedMs={}", round.getId(), elapsedMs(now));
            }
        }

        @Override
        public void emit(String eventName, JsonNode data) {
            events.incrementAndGet();
            sink.tryEmitNext(new Event(eventName, data));
        }

        private void complete() {
            emit(contracts.sseEvents().doneEvent(), baseData(round));
            finish();
        }

        @Override
        public void finish() {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            running.remove(round.getId(), this);
            // 清理 trace 配对状态，避免 roundStates 内存泄漏（票 24）
            agentCallLogs.clearRound(round.getId());
            sink.tryEmitComplete();
            log.info("chat round complete roundId={} events={} costMs={}", round.getId(), events.get(), elapsedMs());
        }

        @Override
        public void fail(Throwable error) {
            ChatRoundService.this.fail(this, error);
        }

        private long elapsedMs() {
            return elapsedMs(System.nanoTime());
        }

        private long elapsedMs(long now) {
            return (now - acceptedNanos) / 1_000_000;
        }
    }
}
