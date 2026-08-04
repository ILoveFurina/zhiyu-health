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
    private final MedCheckinFlow medCheckinFlow;
    private final DemoArsenal demoArsenal;
    private final AppointmentCare appointmentCare;
    private final Emotion emotion;
    private final Voice voice;

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
        this.medCheckinFlow = read(mapper, dir, "med-checkin-flow.json", MedCheckinFlow.class);
        this.demoArsenal = read(mapper, dir, "demo-arsenal.json", DemoArsenal.class);
        this.appointmentCare = read(mapper, dir, "appointment-care.json", AppointmentCare.class);
        this.emotion = read(mapper, dir, "emotion.json", Emotion.class);
        this.voice = read(mapper, dir, "voice.json", Voice.class);
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
    public record ChatRealtime(String websocketPath, List<String> envelopeTypes, List<String> roundStatuses) {
        public ChatRealtime {
            envelopeTypes = List.copyOf(envelopeTypes);
            roundStatuses = List.copyOf(roundStatuses);
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

    public record PrescriptionFlow(
            Map<String, String> statuses,
            Map<String, String> statusLabels,
            Map<String, String> decisions,
            Map<String, String> messageTypes) {
        public PrescriptionFlow {
            statuses = Map.copyOf(statuses);
            statusLabels = Map.copyOf(statusLabels);
            decisions = Map.copyOf(decisions);
            messageTypes = Map.copyOf(messageTypes);
        }
    }

    public record OrderFlow(
            Map<String, String> statuses,
            Map<String, String> statusLabels,
            Map<String, String> decisions,
            Map<String, String> messageTypes,
            Map<String, String> messages) {
        public OrderFlow {
            statuses = Map.copyOf(statuses);
            statusLabels = Map.copyOf(statusLabels);
            decisions = Map.copyOf(decisions);
            messageTypes = Map.copyOf(messageTypes);
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
     * 地址/楼层/材料/注意事项来自 hospitals 表静态 seed 值，非 LLM 生成。
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
}
