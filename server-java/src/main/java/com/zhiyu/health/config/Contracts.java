package com.zhiyu.health.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 跨栈契约基座：仓库根 contracts/ 是 server-java 与 server-py 共享常量的单一事实源。
 * 启动期 fail-fast 加载——契约缺失或损坏属部署错误，必须阻断启动而非带病运行。
 * 消费接线在后续阶段进行，当前只提供不可变 record 供注入。
 */
@Component
public class Contracts {

    /** 默认相对工作目录解析；mvn spring-boot:run / mvn test 的 basedir 均为 server-java/。 */
    private static final String DEFAULT_DIR = "../contracts";

    private final Disclaimer disclaimer;
    private final SseEvents sseEvents;
    private final VisionErrors visionErrors;
    private final UploadLimits uploadLimits;
    private final ChatDefaults chatDefaults;
    private final PrescriptionFlow prescriptionFlow;
    private final OrderFlow orderFlow;
    private final PaymentFlow paymentFlow;
    private final Contraindication contraindication;
    private final Knowledge knowledge;
    private final ChatRealtime chatRealtime;
    private final MedicationKnowledge medicationKnowledge;
    private final MedCheckinFlow medCheckinFlow;
    private final DemoArsenal demoArsenal;
    private final AppointmentCare appointmentCare;
    private final Emotion emotion;
    private final Voice voice;
    private final GuidedRegistration guidedRegistration;
    private final OnlineConsultation onlineConsultation;
    private final DoctorPhotoLimits doctorPhotoLimits;
    private final ConsultationPhotoLimits consultationPhotoLimits;
    private final HealthObservations healthObservations;

    /** Spring 启动入口：构造期完成全部加载，任一文件失败即启动失败。 */
    public Contracts() {
        this(resolveDir());
    }

    private Contracts(Path dir) {
        // 独立 mapper：契约 JSON 统一 snake_case，且允许 _doc 等说明性字段存在。
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.disclaimer = read(mapper, dir, "disclaimer.json", Disclaimer.class);
        this.sseEvents = read(mapper, dir, "sse-events.json", SseEvents.class);
        this.visionErrors = read(mapper, dir, "vision-errors.json", VisionErrors.class);
        this.uploadLimits = read(mapper, dir, "upload-limits.json", UploadLimits.class);
        this.chatDefaults = read(mapper, dir, "chat-defaults.json", ChatDefaults.class);
        this.prescriptionFlow = read(mapper, dir, "prescription-flow.json", PrescriptionFlow.class);
        this.orderFlow = read(mapper, dir, "order-flow.json", OrderFlow.class);
        this.paymentFlow = read(mapper, dir, "payment-flow.json", PaymentFlow.class);
        this.contraindication = read(mapper, dir, "contraindication.json", Contraindication.class);
        this.knowledge = read(mapper, dir, "knowledge.json", Knowledge.class);
        this.chatRealtime = read(mapper, dir, "chat-realtime.json", ChatRealtime.class);
        this.medicationKnowledge = read(mapper, dir, "medication-knowledge.json", MedicationKnowledge.class);
        this.medCheckinFlow = read(mapper, dir, "med-checkin-flow.json", MedCheckinFlow.class);
        this.demoArsenal = read(mapper, dir, "demo-arsenal.json", DemoArsenal.class);
        this.appointmentCare = read(mapper, dir, "appointment-care.json", AppointmentCare.class);
        this.emotion = read(mapper, dir, "emotion.json", Emotion.class);
        this.voice = read(mapper, dir, "voice.json", Voice.class);
        this.guidedRegistration = read(mapper, dir, "guided-registration.json", GuidedRegistration.class);
        this.onlineConsultation = read(mapper, dir, "online-consultation.json", OnlineConsultation.class);
        this.doctorPhotoLimits = read(mapper, dir, "doctor-photo-limits.json", DoctorPhotoLimits.class);
        this.consultationPhotoLimits =
                read(mapper, dir, "consultation-photo-limits.json", ConsultationPhotoLimits.class);
        this.healthObservations = read(mapper, dir, "health-observations.json", HealthObservations.class);
    }

    /** 测试与工具入口：从指定目录加载。 */
    public static Contracts load(Path dir) {
        return new Contracts(dir);
    }

    /** 系统属性或环境变量 CONTRACTS_DIR 优先，默认 ../contracts（相对工作目录）。 */
    public static Path resolveDir() {
        String override = System.getProperty("CONTRACTS_DIR");
        if (override == null || override.isBlank()) {
            override = System.getenv("CONTRACTS_DIR");
        }
        String dir = override == null || override.isBlank() ? DEFAULT_DIR : override;
        return Path.of(dir).toAbsolutePath().normalize();
    }

    private static <T> T read(ObjectMapper mapper, Path dir, String fileName, Class<T> type) {
        Path file = dir.resolve(fileName);
        try {
            return mapper.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new IllegalStateException("跨栈契约加载失败（检查 contracts/ 是否随仓库完整部署）: " + file, e);
        }
    }

    public Disclaimer disclaimer() {
        return disclaimer;
    }

    public SseEvents sseEvents() {
        return sseEvents;
    }

    public VisionErrors visionErrors() {
        return visionErrors;
    }

    public UploadLimits uploadLimits() {
        return uploadLimits;
    }

    public DoctorPhotoLimits doctorPhotoLimits() {
        return doctorPhotoLimits;
    }

    public ConsultationPhotoLimits consultationPhotoLimits() {
        return consultationPhotoLimits;
    }

    /** 报告驱动健康观测（票 61，ADR-0031）：九项白名单指标、血压组合项拆分、来源/核验/沉淀状态与文案。 */
    public HealthObservations healthObservations() {
        return healthObservations;
    }

    public ChatDefaults chatDefaults() {
        return chatDefaults;
    }

    public PrescriptionFlow prescriptionFlow() {
        return prescriptionFlow;
    }

    public OrderFlow orderFlow() {
        return orderFlow;
    }

    public PaymentFlow paymentFlow() {
        return paymentFlow;
    }

    public Contraindication contraindication() {
        return contraindication;
    }

    public Knowledge knowledge() {
        return knowledge;
    }

    public ChatRealtime chatRealtime() {
        return chatRealtime;
    }

    /** C 端通用药品说明书流（票 51，ADR-0028）：SSE 事件名与统一话术。 */
    public MedicationKnowledge medicationKnowledge() {
        return medicationKnowledge;
    }

    public MedCheckinFlow medCheckinFlow() {
        return medCheckinFlow;
    }

    public DemoArsenal demoArsenal() {
        return demoArsenal;
    }

    public AppointmentCare appointmentCare() {
        return appointmentCare;
    }

    /** 情绪反馈（票 44，ADR-0019）：三档情绪标注 + 默认值 + 安抚语映射。 */
    public Emotion emotion() {
        return emotion;
    }

    /** 语音双向（票 45，ADR-0020）：ASR/TTS 开关、格式占位、超时与降级提示。 */
    public Voice voice() {
        return voice;
    }

    /** 智能导诊标准科室与科室号源卡（票 50）：解析结果、卡事件状态、摘要模板与重试字段。 */
    public GuidedRegistration guidedRegistration() {
        return guidedRegistration;
    }

    /** 在线问诊主闭环（票 55）：预问诊场景、草稿与问诊状态机、五步进度、接诊方式、发送者类型、超时与文案。 */
    public OnlineConsultation onlineConsultation() {
        return onlineConsultation;
    }

    /** 免责声明标注：一切 AI 产出必须携带（硬约束 1）。text 为通用文案；
     * tcmText 为中医专属文案（ADR-0024，票 17 拍舌苔），舌诊卡片叠加通用 + 中医两条。 */
    public record Disclaimer(String text, String tcmText) {}

    /** SSE 事件协议：流事件名、工具名→事件名、消息 kind 与事件→kind 映射。 */
    public record SseEvents(
            List<String> streamEvents,
            String redFlagEvent,
            List<String> cardEvents,
            List<String> traceEvents,
            List<String> traceResults,
            String traceErrorCodeUnknown,
            Map<String, String> toolToEvent,
            List<String> messageKinds,
            List<String> aiCardKinds,
            Map<String, String> eventToKind) {
        public SseEvents {
            // 防御性拷贝：Jackson 产出的是可变容器，对外只暴露不可变视图。
            streamEvents = List.copyOf(streamEvents);
            cardEvents = List.copyOf(cardEvents);
            traceEvents = List.copyOf(traceEvents);
            traceResults = List.copyOf(traceResults);
            messageKinds = List.copyOf(messageKinds);
            aiCardKinds = List.copyOf(aiCardKinds);
            toolToEvent = Map.copyOf(toolToEvent);
            eventToKind = Map.copyOf(eventToKind);
        }

        // 流事件名按契约顺序固定为 [meta, knowledge, token, message, done]，
        // 顺序由 ContractsTest.sseEventProtocolIsComplete 钉死。
        public String metaEvent() {
            return streamEvents.get(0);
        }

        public String knowledgeEvent() {
            return streamEvents.get(1);
        }

        public String tokenEvent() {
            return streamEvents.get(2);
        }

        public String messageEvent() {
            return streamEvents.get(3);
        }

        public String doneEvent() {
            return streamEvents.get(4);
        }

        /** 工具进度事件两态（票 24）：tool_start/tool_end，无序、可穿插。 */
        public String toolStartEvent() {
            return traceEvents.get(0);
        }

        public String toolEndEvent() {
            return traceEvents.get(1);
        }

        /** 事件名是否属于工具进度事件（trace）：与 stream/card 事件命名空间隔离。 */
        public boolean isTraceEvent(String eventName) {
            return traceEvents.contains(eventName);
        }

        /** tool_end 结果枚举白名单（硬约束 5：result 只接受契约白名单值）。 */
        public boolean isTraceResult(String value) {
            return traceResults.contains(value);
        }
    }

    /** 报告解读错误码集合与用户可见文案（文案以 server-java 出口为准）。 */
    public record VisionErrors(List<String> codes, Map<String, String> messages) {
        public VisionErrors {
            codes = List.copyOf(codes);
            messages = Map.copyOf(messages);
        }
    }

    /** 报告上传限制：两端入口校验必须一致。 */
    public record UploadLimits(
            long maxFileBytes,
            long maxTotalBytes,
            int minFiles,
            int maxFiles,
            List<String> allowedTypes,
            boolean pdfSingleFile) {
        public UploadLimits {
            allowedTypes = List.copyOf(allowedTypes);
        }

        /** 允许清单中的 PDF 类型（唯一非图片项）。 */
        public String pdfType() {
            return allowedTypes.stream()
                    .filter(type -> !type.startsWith("image/"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("契约 upload-limits.json 缺少 PDF 类型"));
        }

        /** 允许清单中的图片子集（PDF 走独立校验分支）。 */
        public List<String> imageTypes() {
            return allowedTypes.stream()
                    .filter(type -> type.startsWith("image/"))
                    .toList();
        }
    }

    /** 医生头像上传限制（票 54）：B 端 admin 上传校验的单一事实源，区别于报告上传。 */
    public record DoctorPhotoLimits(long maxBytes, List<String> allowedTypes, int maxFiles) {
        public DoctorPhotoLimits {
            allowedTypes = List.copyOf(allowedTypes);
        }
    }

    /** 在线问诊交流图片上传限制（票 58，ADR-0029）：患者发图校验的事实源；与医生头像同值但语义独立（图片是消息本体，不降级）。 */
    public record ConsultationPhotoLimits(long maxBytes, List<String> allowedTypes, int maxFiles) {
        public ConsultationPhotoLimits {
            allowedTypes = List.copyOf(allowedTypes);
        }
    }

    /** 对话默认值与经纬度校验范围。 */
    public record ChatDefaults(
            String effortDefault,
            String scenarioDefault,
            List<String> effortChoices,
            List<String> scenarios,
            double longitudeMin,
            double longitudeMax,
            double latitudeMin,
            double latitudeMax) {
        public ChatDefaults {
            effortChoices = List.copyOf(effortChoices);
            scenarios = List.copyOf(scenarios);
        }
    }

    /** C 端实时对话的结构化信封与持久化状态。 */
    public record ChatRealtime(
            String websocketPath,
            List<String> envelopeTypes,
            List<String> roundStatuses,
            List<String> chatOptionalFields) {
        public ChatRealtime {
            envelopeTypes = List.copyOf(envelopeTypes);
            roundStatuses = List.copyOf(roundStatuses);
            chatOptionalFields = List.copyOf(chatOptionalFields);
        }

        // 信封类型按契约顺序固定为 [chat, accepted, event, error]，
        // 轮次状态按契约顺序固定为 [ACCEPTED, RUNNING, COMPLETED, FAILED]，
        // 顺序由 ContractsTest 钉死。
        public String chatEnvelope() {
            return envelopeTypes.get(0);
        }

        public String acceptedEnvelope() {
            return envelopeTypes.get(1);
        }

        public String eventEnvelope() {
            return envelopeTypes.get(2);
        }

        public String errorEnvelope() {
            return envelopeTypes.get(3);
        }

        public String acceptedStatus() {
            return roundStatuses.get(0);
        }

        public String runningStatus() {
            return roundStatuses.get(1);
        }

        public String completedStatus() {
            return roundStatuses.get(2);
        }

        public String failedStatus() {
            return roundStatuses.get(3);
        }
    }

    /**
     * C 端通用药品说明书流（票 51，ADR-0028）：server-py SSE 流式输出 LLM 通用药品知识，
     * 事件序列固定为 token×N → done；consult_professional 为流尾统一话术，unknown_drug
     * 为未识别药名话术。禁忌判定仅留 B 端开方链路（ADR-0016），本契约不含任何个性化安全出口。
     */
    public record MedicationKnowledge(List<String> streamEvents, Map<String, String> messages) {
        public MedicationKnowledge {
            streamEvents = List.copyOf(streamEvents);
            messages = Map.copyOf(messages);
        }

        // 流事件名按契约顺序固定为 [token, done]，顺序由 ContractsTest 钉死。
        public String tokenEvent() {
            return streamEvents.get(0);
        }

        public String doneEvent() {
            return streamEvents.get(1);
        }

        /** 流尾统一话术：通用说明书不做个性化适用判断，引导咨询医生或药师。 */
        public String consultProfessional() {
            return messages.get("consult_professional");
        }

        /** 未找到药品通用信息时的话术。 */
        public String unknownDrug() {
            return messages.get("unknown_drug");
        }
    }

    public record PrescriptionFlow(
            Map<String, String> statuses,
            Map<String, String> statusLabels,
            Map<String, String> decisions,
            Map<String, String> sourceTypes,
            Map<String, String> sourceTypeLabels,
            Map<String, String> messageTypes,
            Map<String, ReviewMessage> messages) {
        public PrescriptionFlow {
            statuses = Map.copyOf(statuses);
            statusLabels = Map.copyOf(statusLabels);
            decisions = Map.copyOf(decisions);
            sourceTypes = Map.copyOf(sourceTypes);
            sourceTypeLabels = Map.copyOf(sourceTypeLabels);
            messageTypes = Map.copyOf(messageTypes);
            messages = Map.copyOf(messages);
        }

        /** 审核结果站内消息文案（票 60）：键为审核结果 approved/rejected，患者向确定性文案。 */
        public record ReviewMessage(String title, String content) {}
    }

    public record OrderFlow(
            Map<String, String> statuses,
            Map<String, String> statusLabels,
            Map<String, String> decisions,
            Map<String, String> messages) {
        public OrderFlow {
            statuses = Map.copyOf(statuses);
            statusLabels = Map.copyOf(statusLabels);
            decisions = Map.copyOf(decisions);
            messages = Map.copyOf(messages);
        }
    }

    public record PaymentFlow(
            Map<String, String> statuses,
            Map<String, String> statusLabels,
            Map<String, String> decisions,
            Map<String, String> messages) {
        public PaymentFlow {
            statuses = Map.copyOf(statuses);
            statusLabels = Map.copyOf(statusLabels);
            decisions = Map.copyOf(decisions);
            messages = Map.copyOf(messages);
        }
    }

    public record Contraindication(
            Map<String, String> decisions,
            Map<String, String> messageTypes,
            Map<String, String> messages,
            String advice) {
        public Contraindication {
            decisions = Map.copyOf(decisions);
            messageTypes = Map.copyOf(messageTypes);
            messages = Map.copyOf(messages);
        }
    }

    /** 知识增强模式：知识源二态 rag/graph + 自动降级、向量检索参数（ADR-0010）。 */
    public record Knowledge(
            List<String> knowledgeSources,
            String noneSource,
            Map<String, String> defaultByScenario,
            String knowledgeMetaEvent,
            List<String> knowledgeStatus,
            int embeddingDimension,
            String vectorColumn,
            int searchTopK,
            double similarityThreshold) {
        public Knowledge {
            knowledgeSources = List.copyOf(knowledgeSources);
            knowledgeStatus = List.copyOf(knowledgeStatus);
            defaultByScenario = Map.copyOf(defaultByScenario);
        }
    }

    /** 服药打卡流程：状态机 PENDING->CHECKED、决定值、站内消息类型与时间线类型（ADR-0017）。 */
    public record MedCheckinFlow(
            Map<String, String> statuses,
            Map<String, String> statusLabels,
            Map<String, String> decisions,
            Map<String, String> messageTypes,
            Map<String, String> timelineTypes) {
        public MedCheckinFlow {
            statuses = Map.copyOf(statuses);
            statusLabels = Map.copyOf(statusLabels);
            decisions = Map.copyOf(decisions);
            messageTypes = Map.copyOf(messageTypes);
            timelineTypes = Map.copyOf(timelineTypes);
        }
    }

    /**
     * 演示武器包常量（票 25，ADR-0022）：重置确认短语、知识源开关值域与 Redis 键、
     * 知识基线数量断言阈值、冻结状态码。基线数字来自 seed.sql 与 deploy/neo4j/seed.cypher。
     */
    public record DemoArsenal(
            String resetConfirmPhrase,
            List<String> knowledgeSourceValues,
            String knowledgeSourceDefault,
            String knowledgeSourceRedisKey,
            Map<String, Integer> knowledgeBaselines,
            int resetFreezeStatus,
            String resetFreezeMessage) {
        public DemoArsenal {
            knowledgeSourceValues = List.copyOf(knowledgeSourceValues);
            knowledgeBaselines = Map.copyOf(knowledgeBaselines);
        }
    }

    /**
     * 挂号后就诊指引卡（票 43）：挂号成功由 server-java 事务内写一条 in_app_messages，
     * type=message_type、title=title、content 存 content_schema 定义的结构化 JSON。
     * 地址/楼层/材料/注意事项来自 hospital_campuses 表静态 seed 值（票 49），非 LLM 生成。
     */
    public record AppointmentCare(String messageType, String title, List<String> contentSchema) {
        public AppointmentCare {
            contentSchema = List.copyOf(contentSchema);
        }
    }

    /**
     * 情绪反馈（票 44，ADR-0019）：C 端 Agent 回复携带的三档情绪标注 calm/anxious/fearful，
     * 驱动 AI 气泡配色与安抚语。emotion 挂 message 事件（_carried_by=message），
     * 落 messages.emotion 列供历史回看复现情绪色。calm 无安抚语（映射缺省即无）。
     */
    public record Emotion(
            List<String> emotions,
            @JsonProperty("default") String defaultEmotion,
            @JsonProperty("_carried_by") String carriedBy,
            Map<String, String> soothingTexts) {
        public Emotion {
            emotions = List.copyOf(emotions);
            soothingTexts = Map.copyOf(soothingTexts);
        }

        /** emotion 是否属于契约白名单（落库前校验，防脏值写入 messages.emotion）。 */
        public boolean isKnown(String value) {
            return value != null && emotions.contains(value);
        }

        /** 安抚语：calm 无（映射缺省返回 null）；anxious/fearful 各一条确定性文案。 */
        public String soothingText(String value) {
            return value == null ? null : soothingTexts.get(value);
        }
    }

    /**
     * 语音双向（票 45，ADR-0020）：ASR 输入与 TTS 播报的跨栈开关、格式占位与降级提示。
     *
     * 结构一次性钉死：火山语音开通前 asrEnabled/ttsEnabled=false、格式字段留 null
     * （骨架+fake 阶段）；开通后只填值不改结构，不双栈二次发版。ASR/TTS 不进
     * agent_call_logs trace，仅 server-java 入口审计记调用类型+参数类型+结果码/长度，
     * 不记音频与识别/合成文字原文（硬约束 5）。
     */
    public record Voice(
            boolean asrEnabled,
            String asrFormat,
            int asrTimeoutMs,
            int asrMaxDurationMs,
            boolean ttsEnabled,
            String ttsFormat,
            int ttsTimeoutMs,
            String ttsVoice,
            List<String> errorCodes,
            String degradeHint) {
        public Voice {
            errorCodes = List.copyOf(errorCodes);
        }

        /** 错误码是否属于契约白名单（出口映射前校验，防脏值透传）。 */
        public boolean isKnownCode(String code) {
            return code != null && errorCodes.contains(code);
        }
    }

    /**
     * 智能导诊标准科室与科室号源卡（票 50）：标准科室解析结果、科室号源卡事件状态、
     * 确定性摘要模板与失败/重试文案。编排代码（非 LLM）保证查询触发与摘要拼装；
     * 业务侧暂不消费模板，当前主要供测试钉值与双端一致性。
     */
    public record GuidedRegistration(
            List<String> resolutionStatuses,
            String cardEvent,
            List<String> cardStatuses,
            String retryRequestField,
            Map<String, String> summaryTemplates,
            Map<String, String> timeSlotLabels,
            String retryUserText) {
        public GuidedRegistration {
            resolutionStatuses = List.copyOf(resolutionStatuses);
            cardStatuses = List.copyOf(cardStatuses);
            summaryTemplates = Map.copyOf(summaryTemplates);
            timeSlotLabels = Map.copyOf(timeSlotLabels);
        }
    }

    /**
     * 在线问诊主闭环（票 55，Spec 0003）：预问诊场景值、草稿状态机（COLLECTING/PENDING_CONFIRM/SUBMITTED）、
     * 问诊状态机（WAITING_DOCTOR→IN_PROGRESS→COMPLETED，旁路 CANCELLED/EXPIRED）、C 端固定五步进度、
     * 接诊方式（TEXT/VIDEO，VIDEO 仅模拟）、医患消息发送者类型、默认接诊超时与全部用户文案。
     * Java 侧零私写枚举：状态/方式/发送者/标签/文案一律经本 record 取值。
     */
    public record OnlineConsultation(
            String scenario,
            Map<String, String> draftStatuses,
            Map<String, String> draftStatusLabels,
            Map<String, String> statuses,
            Map<String, String> statusLabels,
            List<String> activeStatuses,
            Map<String, String> decisions,
            List<ProgressStep> progressSteps,
            Map<String, String> consultMethods,
            Map<String, String> consultMethodLabels,
            Map<String, String> senderTypes,
            List<String> messageKinds,
            int acceptTimeoutSeconds,
            Map<String, String> timelineTypes,
            List<String> summaryFields,
            Map<String, String> summaryFieldLabels,
            String summaryEventField,
            Map<String, String> texts,
            FollowUp followUp) {
        public OnlineConsultation {
            draftStatuses = Map.copyOf(draftStatuses);
            draftStatusLabels = Map.copyOf(draftStatusLabels);
            statuses = Map.copyOf(statuses);
            statusLabels = Map.copyOf(statusLabels);
            activeStatuses = List.copyOf(activeStatuses);
            decisions = Map.copyOf(decisions);
            progressSteps = List.copyOf(progressSteps);
            consultMethods = Map.copyOf(consultMethods);
            consultMethodLabels = Map.copyOf(consultMethodLabels);
            senderTypes = Map.copyOf(senderTypes);
            messageKinds = List.copyOf(messageKinds);
            timelineTypes = Map.copyOf(timelineTypes);
            summaryFields = List.copyOf(summaryFields);
            summaryFieldLabels = Map.copyOf(summaryFieldLabels);
            texts = Map.copyOf(texts);
        }

        /** 状态是否属于终态分支（CANCELLED/EXPIRED）：不占五步进度步，C 端展示 terminal_hint。 */
        public boolean isTerminal(String status) {
            return !isProgressStatus(status);
        }

        /** 状态是否对应五步进度中的一个步骤键（WAITING_DOCTOR/IN_PROGRESS/COMPLETED）。 */
        public boolean isProgressStatus(String status) {
            return progressSteps.stream().anyMatch(step -> step.key().equals(status));
        }

        /** 接诊方式是否为契约白名单值（start-method 入口校验，防脏值写库）。 */
        public boolean isKnownConsultMethod(String method) {
            return method != null && consultMethods.containsValue(method);
        }

        /** 消息类型是否为契约白名单值（票 58：写库前校验，防脏值入库）。 */
        public boolean isKnownMessageKind(String kind) {
            return kind != null && messageKinds.contains(kind);
        }

        /** C 端五步进度的一步；key 与问诊状态值同源的步骤直接复用状态值。 */
        public record ProgressStep(String key, String label) {}

        /** 随访关怀站内消息（票 60）：COMPLETED 时同事务 eager 生成，visible_at 延迟 delayDays 天可见。 */
        public record FollowUp(String messageType, String title, String content, int delayDays) {}
    }

    /**
     * 报告驱动健康观测（票 61，ADR-0031）：九项白名单指标（别名/值类型/规范单位/单位别名/血型分类）、
     * 血压组合项拆分规则、来源类型、核验状态、患者决定与报告项沉淀状态及中文文案。
     * LLM/server-py 绝不输出 metric_code，只有 server-java 按本契约做确定性映射并写业务库。
     */
    public record HealthObservations(
            Map<String, String> valueTypes,
            Map<String, Metric> metrics,
            BloodPressurePair bloodPressurePair,
            Map<String, String> sourceTypes,
            Map<String, String> sourceDisplayZh,
            Map<String, String> verificationStatuses,
            Map<String, String> verificationDisplayZh,
            Map<String, String> patientDecisions,
            Map<String, String> itemStates,
            Map<String, String> itemStateDisplayZh) {
        public HealthObservations {
            valueTypes = Map.copyOf(valueTypes);
            // Map.copyOf 不保迭代顺序；指标声明顺序即概要/趋势展示顺序，必须用 LinkedHashMap 保序
            metrics = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metrics));
            sourceTypes = Map.copyOf(sourceTypes);
            sourceDisplayZh = Map.copyOf(sourceDisplayZh);
            verificationStatuses = Map.copyOf(verificationStatuses);
            verificationDisplayZh = Map.copyOf(verificationDisplayZh);
            patientDecisions = Map.copyOf(patientDecisions);
            // item_states 含 _doc 说明性字段：Map 组件会把说明键一并载入，消费前剔除
            itemStates = itemStates.entrySet().stream()
                    .filter(entry -> !entry.getKey().startsWith("_"))
                    .collect(java.util.stream.Collectors.collectingAndThen(
                            java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, java.util.LinkedHashMap::new),
                            java.util.Collections::unmodifiableMap));
            itemStateDisplayZh = Map.copyOf(itemStateDisplayZh);
        }

        /** 白名单指标代码，按契约声明顺序（概要与趋势的固定展示顺序）。 */
        public List<String> metricCodes() {
            return List.copyOf(metrics.keySet());
        }

        public String numericValueType() {
            return valueTypes.get("numeric");
        }

        public String categoricalValueType() {
            return valueTypes.get("categorical");
        }

        public String reportAiSource() {
            return sourceTypes.get("report_ai");
        }

        public String userCorrectionSource() {
            return sourceTypes.get("user_correction");
        }

        public String unverifiedStatus() {
            return verificationStatuses.get("unverified");
        }

        public String userConfirmedStatus() {
            return verificationStatuses.get("user_confirmed");
        }

        public String rejectedStatus() {
            return verificationStatuses.get("rejected");
        }

        public String supersededStatus() {
            return verificationStatuses.get("superseded");
        }

        /** 单项指标：分类指标（血型）无 canonical_unit/unit_aliases，缺省字段归一为空容器。 */
        public record Metric(
                String nameZh,
                String valueType,
                String canonicalUnit,
                List<String> aliases,
                Map<String, String> unitAliases,
                boolean allowUnitMissing,
                List<String> categories,
                Map<String, String> categoryDisplayZh,
                Map<String, String> categoryAliases) {
            public Metric {
                aliases = aliases == null ? List.of() : List.copyOf(aliases);
                unitAliases = unitAliases == null ? Map.of() : Map.copyOf(unitAliases);
                categories = categories == null ? List.of() : List.copyOf(categories);
                categoryDisplayZh = categoryDisplayZh == null ? Map.of() : Map.copyOf(categoryDisplayZh);
                categoryAliases = categoryAliases == null ? Map.of() : Map.copyOf(categoryAliases);
            }
        }

        /** 血压组合项（如“血压 120/80 mmHg”）：值匹配 value_pattern 才拆成收缩压/舒张压两条观测。 */
        public record BloodPressurePair(
                List<String> aliases,
                String valuePattern,
                String systolicCode,
                String diastolicCode,
                String canonicalUnit,
                Map<String, String> unitAliases,
                boolean allowUnitMissing) {
            public BloodPressurePair {
                aliases = List.copyOf(aliases);
                unitAliases = Map.copyOf(unitAliases);
            }
        }
    }
}
