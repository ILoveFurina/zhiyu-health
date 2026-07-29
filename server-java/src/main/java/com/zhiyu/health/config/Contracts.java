package com.zhiyu.health.config;

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
    private final Contraindication contraindication;

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
        this.contraindication = read(mapper, dir, "contraindication.json", Contraindication.class);
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

    public Contraindication contraindication() {
        return contraindication;
    }

    /** 免责声明标注：一切 AI 产出必须携带（硬约束 1）。 */
    public record Disclaimer(String text) {}

    /** SSE 事件协议：流事件名、工具名→事件名、消息 kind 与事件→kind 映射。 */
    public record SseEvents(
            List<String> streamEvents,
            String redFlagEvent,
            List<String> cardEvents,
            Map<String, String> toolToEvent,
            List<String> messageKinds,
            List<String> aiCardKinds,
            Map<String, String> eventToKind) {
        public SseEvents {
            // 防御性拷贝：Jackson 产出的是可变容器，对外只暴露不可变视图。
            streamEvents = List.copyOf(streamEvents);
            cardEvents = List.copyOf(cardEvents);
            messageKinds = List.copyOf(messageKinds);
            aiCardKinds = List.copyOf(aiCardKinds);
            toolToEvent = Map.copyOf(toolToEvent);
            eventToKind = Map.copyOf(eventToKind);
        }

        // 流事件名按契约顺序固定为 [meta, token, message, done]，
        // 顺序由 ContractsTest.sseEventProtocolIsComplete 钉死。
        public String metaEvent() {
            return streamEvents.get(0);
        }

        public String tokenEvent() {
            return streamEvents.get(1);
        }

        public String messageEvent() {
            return streamEvents.get(2);
        }

        public String doneEvent() {
            return streamEvents.get(3);
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
}
